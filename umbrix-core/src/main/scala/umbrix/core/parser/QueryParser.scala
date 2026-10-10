package umbrix.core.parser

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import scala.annotation.tailrec
import scala.util.control.NonFatal
import umbrix.core.model.LogLevel
import umbrix.core.model.QueryAst

/**
 * Diagnostic record describing a syntax error encountered during query parsing.
 *
 * @param message human-readable error description
 * @param characterOffset 0-based character offset where the error occurred
 */
case class QueryParseError(message: String, characterOffset: Int)

/**
 * Internal token representation produced by the query lexer.
 */
sealed trait QueryToken {
  def offset: Int
}

object QueryToken {
  case class Term(text: String, isQuoted: Boolean, offset: Int, endOffset: Int) extends QueryToken
  case class RegexTerm(pattern: String, offset: Int) extends QueryToken
  case class LevelTerm(level: LogLevel, offset: Int) extends QueryToken
  case class SinceTerm(epochMicros: Long, offset: Int) extends QueryToken
  case class UntilTerm(epochMicros: Long, offset: Int) extends QueryToken
  case class CaseModifier(offset: Int) extends QueryToken
  case class AndToken(offset: Int) extends QueryToken
  case class OrToken(offset: Int) extends QueryToken
  case class NotToken(offset: Int) extends QueryToken
  case class OpenParen(offset: Int) extends QueryToken
  case class CloseParen(offset: Int) extends QueryToken
}

/**
 * ReDoS-immune functional recursive descent query parser.
 *
 * Compiles user input queries into a structured `QueryAst`. Guarantees linear-time
 * parsing and regex compilation using Google RE2J.
 */
object QueryParser {

  /**
   * Parses the user query string into a structured `QueryAst`.
   *
   * @param query raw input search text
   * @return `Right(ast)` if query is syntactically valid, or `Left(QueryParseError)` on syntax failure
   */
  def parse(query: String): Either[QueryParseError, QueryAst] = {
    val trimmedQuery: String = if (query == null) "" else query.trim
    if (trimmedQuery.isEmpty) {
      Right(QueryAst.Empty)
    } else {
      tokenize(trimmedQuery).flatMap { tokens =>
        if (tokens.isEmpty) {
          Right(QueryAst.Empty)
        } else {
          val parserState: ParserState = ParserState(tokens, trimmedQuery)
          parserState.parseExpression().flatMap { ast =>
            if (parserState.hasNext) {
              val unparsedToken: QueryToken = parserState.peek
              Left(QueryParseError(s"Unexpected token '${tokenRepresentation(unparsedToken)}'", unparsedToken.offset))
            } else {
              Right(ast)
            }
          }
        }
      }
    }
  }

  /**
   * Tokenizes raw query input into a sequence of query tokens.
   */
  private def tokenize(input: String): Either[QueryParseError, List[QueryToken]] = {
    val length: Int = input.length
    var index: Int = 0
    var tokensAccumulator: List[QueryToken] = Nil
    var encounteredError: Option[QueryParseError] = None

    while (index < length && encounteredError.isEmpty) {
      val currentChar: Char = input.charAt(index)

      if (Character.isWhitespace(currentChar)) {
        index += 1
      } else if (currentChar == '(') {
        tokensAccumulator = QueryToken.OpenParen(index) :: tokensAccumulator
        index += 1
      } else if (currentChar == ')') {
        tokensAccumulator = QueryToken.CloseParen(index) :: tokensAccumulator
        index += 1
      } else if (currentChar == '"' || currentChar == '\'') {
        // Quoted literal
        val quoteChar: Char = currentChar
        val startOffset: Int = index
        index += 1
        val stringBuffer: java.lang.StringBuilder = new java.lang.StringBuilder()
        var quoteClosed: Boolean = false

        while (index < length && !quoteClosed) {
          val c: Char = input.charAt(index)
          if (c == '\\' && index + 1 < length) {
            stringBuffer.append(input.charAt(index + 1))
            index += 2
          } else if (c == quoteChar) {
            quoteClosed = true
            index += 1
          } else {
            stringBuffer.append(c)
            index += 1
          }
        }

        if (!quoteClosed) {
          encounteredError = Some(QueryParseError(s"Unclosed quote starting at offset $startOffset", startOffset))
        } else {
          tokensAccumulator = QueryToken.Term(stringBuffer.toString, isQuoted = true, startOffset, index) :: tokensAccumulator
        }
      } else if (currentChar == '/') {
        // Regex literal /pattern/
        val startOffset: Int = index
        index += 1
        val regexBuffer: java.lang.StringBuilder = new java.lang.StringBuilder()
        var regexClosed: Boolean = false

        while (index < length && !regexClosed) {
          val c: Char = input.charAt(index)
          if (c == '\\' && index + 1 < length) {
            regexBuffer.append('\\')
            regexBuffer.append(input.charAt(index + 1))
            index += 2
          } else if (c == '/') {
            regexClosed = true
            index += 1
          } else {
            regexBuffer.append(c)
            index += 1
          }
        }

        if (!regexClosed) {
          encounteredError = Some(QueryParseError(s"Unclosed regular expression starting at offset $startOffset", startOffset))
        } else {
          tokensAccumulator = QueryToken.RegexTerm(regexBuffer.toString, startOffset) :: tokensAccumulator
        }
      } else {
        // Word or prefixed term
        val startOffset: Int = index
        while (index < length && !Character.isWhitespace(input.charAt(index)) &&
              input.charAt(index) != '(' && input.charAt(index) != ')' &&
              input.charAt(index) != '"' && input.charAt(index) != '\'') {
          index += 1
        }

        val rawWord: String = input.substring(startOffset, index)
        val upperWord: String = rawWord.toUpperCase

        if (upperWord == "AND" || rawWord == "&&") {
          tokensAccumulator = QueryToken.AndToken(startOffset) :: tokensAccumulator
        } else if (upperWord == "OR" || rawWord == "||") {
          tokensAccumulator = QueryToken.OrToken(startOffset) :: tokensAccumulator
        } else if (upperWord == "NOT" || rawWord == "!") {
          tokensAccumulator = QueryToken.NotToken(startOffset) :: tokensAccumulator
        } else if (rawWord.equalsIgnoreCase("case:sensitive") || rawWord.equalsIgnoreCase("case:true")) {
          tokensAccumulator = QueryToken.CaseModifier(startOffset) :: tokensAccumulator
        } else if (rawWord.toLowerCase.startsWith("level:")) {
          val levelString: String = rawWord.substring(6)
          LogLevel.fromString(levelString) match {
            case Some(level) =>
              tokensAccumulator = QueryToken.LevelTerm(level, startOffset) :: tokensAccumulator
            case None =>
              encounteredError = Some(QueryParseError(s"Unknown log level '$levelString'", startOffset))
          }
        } else if (rawWord.toLowerCase.startsWith("since:")) {
          val timeString: String = rawWord.substring(6)
          parseTimestampToMicros(timeString) match {
            case Some(micros) =>
              tokensAccumulator = QueryToken.SinceTerm(micros, startOffset) :: tokensAccumulator
            case None =>
              encounteredError = Some(QueryParseError(s"Invalid timestamp format '$timeString' (expected ISO-8601)", startOffset))
          }
        } else if (rawWord.toLowerCase.startsWith("until:")) {
          val timeString: String = rawWord.substring(6)
          parseTimestampToMicros(timeString) match {
            case Some(micros) =>
              tokensAccumulator = QueryToken.UntilTerm(micros, startOffset) :: tokensAccumulator
            case None =>
              encounteredError = Some(QueryParseError(s"Invalid timestamp format '$timeString' (expected ISO-8601)", startOffset))
          }
        } else if (rawWord.toLowerCase.startsWith("regex:")) {
          val regexString: String = rawWord.substring(6)
          tokensAccumulator = QueryToken.RegexTerm(regexString, startOffset) :: tokensAccumulator
        } else {
          tokensAccumulator = QueryToken.Term(rawWord, isQuoted = false, startOffset, index) :: tokensAccumulator
        }
      }
    }

    encounteredError match {
      case Some(error) => Left(error)
      case None => Right(tokensAccumulator.reverse)
    }
  }

  private def parseTimestampToMicros(isoString: String): Option[Long] = {
    try {
      val instant: Instant = {
        if (isoString.contains('T') || isoString.endsWith("Z")) {
          Instant.parse(if (isoString.endsWith("Z")) isoString else isoString + "Z")
        } else {
          LocalDateTime.parse(isoString.replace(' ', 'T')).toInstant(ZoneOffset.UTC)
        }
      }
      Some(instant.getEpochSecond * 1000000L + (instant.getNano / 1000L))
    } catch {
      case NonFatal(_) => None
    }
  }

  private def tokenRepresentation(token: QueryToken): String = {
    token match {
      case QueryToken.Term(text, _, _, _) => text
      case QueryToken.RegexTerm(p, _) => s"/$p/"
      case QueryToken.LevelTerm(l, _) => s"level:$l"
      case QueryToken.SinceTerm(m, _) => s"since:$m"
      case QueryToken.UntilTerm(m, _) => s"until:$m"
      case QueryToken.CaseModifier(_) => "case:sensitive"
      case QueryToken.AndToken(_) => "AND"
      case QueryToken.OrToken(_) => "OR"
      case QueryToken.NotToken(_) => "NOT"
      case QueryToken.OpenParen(_) => "("
      case QueryToken.CloseParen(_) => ")"
    }
  }

  /**
   * Stateful recursive-descent parser working over a token stream.
   */
  private class ParserState(tokens: List[QueryToken], rawInput: String) {
    private var currentTokens: List[QueryToken] = tokens

    def hasNext: Boolean = currentTokens.nonEmpty
    def peek: QueryToken = currentTokens.head
    def advance(): QueryToken = {
      val headToken: QueryToken = currentTokens.head
      currentTokens = currentTokens.tail
      headToken
    }

    def parseExpression(): Either[QueryParseError, QueryAst] = {
      parseOr()
    }

    private def parseOr(): Either[QueryParseError, QueryAst] = {
      parseAnd().flatMap { initialLeft =>
        var leftExpression: QueryAst = initialLeft
        var parseError: Option[QueryParseError] = None

        while (hasNext && isOrOperator(peek) && parseError.isEmpty) {
          advance() // consume OR
          parseAnd() match {
            case Right(rightExpression) =>
              leftExpression = QueryAst.Or(leftExpression, rightExpression)
            case Left(err) =>
              parseError = Some(err)
          }
        }

        parseError match {
          case Some(err) => Left(err)
          case None => Right(leftExpression)
        }
      }
    }

    private def parseAnd(): Either[QueryParseError, QueryAst] = {
      parseNot().flatMap { initialLeft =>
        var leftExpression: QueryAst = initialLeft
        var parseError: Option[QueryParseError] = None

        while (hasNext && isAndCandidate(peek) && parseError.isEmpty) {
          if (isAndOperator(peek)) {
            advance() // consume explicit AND
          }

          if (hasNext && !isOrOperator(peek) && !isCloseParen(peek)) {
            parseNot() match {
              case Right(rightExpression) =>
                leftExpression = QueryAst.And(leftExpression, rightExpression)
              case Left(err) =>
                parseError = Some(err)
            }
          } else {
            parseError = Some(QueryParseError("Dangling AND operator", peek.offset))
          }
        }

        parseError match {
          case Some(err) => Left(err)
          case None => Right(leftExpression)
        }
      }
    }

    private def parseNot(): Either[QueryParseError, QueryAst] = {
      if (hasNext && isNotOperator(peek)) {
        val notToken: QueryToken = advance()
        if (!hasNext) {
          Left(QueryParseError("Dangling NOT operator at end of expression", notToken.offset))
        } else {
          parseNot().map(expr => QueryAst.Not(expr))
        }
      } else {
        parsePrimary()
      }
    }

    private def parsePrimary(): Either[QueryParseError, QueryAst] = {
      if (!hasNext) {
        Left(QueryParseError("Unexpected end of input, expected term or expression", 0))
      } else {
        peek match {
          case QueryToken.OpenParen(offset) =>
            advance()
            parseExpression().flatMap { innerExpr =>
              if (hasNext && isOpenParen(peek) == false && isCloseParen(peek)) {
                advance() // consume ')'
                Right(innerExpr)
              } else {
                Left(QueryParseError(s"Unclosed parenthesis opened at offset $offset", offset))
              }
            }
          case QueryToken.CaseModifier(offset) =>
            advance()
            if (hasNext) {
              parseTerm(isCaseSensitive = true)
            } else {
              Left(QueryParseError("Dangling case:sensitive modifier without target term", offset))
            }
          case _ =>
            parseTerm(isCaseSensitive = false)
        }
      }
    }

    private def parseTerm(isCaseSensitive: Boolean): Either[QueryParseError, QueryAst] = {
      if (!hasNext) {
        Left(QueryParseError("Expected search term", 0))
      } else {
        advance() match {
          case QueryToken.Term(text, isQuoted, offset, endOffset) =>
            if (isQuoted) {
              Right(QueryAst.Literal(text, caseSensitive = isCaseSensitive))
            } else {
              var phraseText: String = text
              var lastEndOffset: Int = endOffset
              while (hasNext && isUnquotedTerm(peek)) {
                val nextTerm: QueryToken.Term = advance().asInstanceOf[QueryToken.Term]
                val spacing: String = rawInput.substring(lastEndOffset, nextTerm.offset)
                phraseText = phraseText + spacing + nextTerm.text
                lastEndOffset = nextTerm.endOffset
              }
              Right(QueryAst.Literal(phraseText, caseSensitive = isCaseSensitive))
            }
          case QueryToken.RegexTerm(patternText, offset) =>
            try {
              val compiledPattern: Pattern = Pattern.compile(patternText, Pattern.MULTILINE)
              Right(QueryAst.Regex(compiledPattern))
            } catch {
              case err: PatternSyntaxException =>
                Left(QueryParseError(s"Invalid regular expression '$patternText': ${err.getMessage}", offset))
            }
          case QueryToken.LevelTerm(level, _) =>
            Right(QueryAst.LevelFilter(level))
          case QueryToken.SinceTerm(micros, _) =>
            Right(QueryAst.TimeRange(sinceMicros = Some(micros), untilMicros = None))
          case QueryToken.UntilTerm(micros, _) =>
            Right(QueryAst.TimeRange(sinceMicros = None, untilMicros = Some(micros)))
          case other =>
            Left(QueryParseError(s"Unexpected token '${tokenRepresentation(other)}'", other.offset))
        }
      }
    }

    private def isUnquotedTerm(token: QueryToken): Boolean = {
      token match {
        case QueryToken.Term(_, false, _, _) => true
        case _ => false
      }
    }

    private def isOrOperator(token: QueryToken): Boolean = {
      token.isInstanceOf[QueryToken.OrToken]
    }

    private def isAndOperator(token: QueryToken): Boolean = {
      token.isInstanceOf[QueryToken.AndToken]
    }

    private def isNotOperator(token: QueryToken): Boolean = {
      token.isInstanceOf[QueryToken.NotToken]
    }

    private def isCloseParen(token: QueryToken): Boolean = {
      token.isInstanceOf[QueryToken.CloseParen]
    }

    private def isOpenParen(token: QueryToken): Boolean = {
      token.isInstanceOf[QueryToken.OpenParen]
    }

    private def isAndCandidate(token: QueryToken): Boolean = {
      token match {
        case QueryToken.OrToken(_) | QueryToken.CloseParen(_) => false
        case _ => true
      }
    }
  }
}
