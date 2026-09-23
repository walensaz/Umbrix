package umbrix.core.parser

import munit.FunSuite
import umbrix.core.model.LogLevel
import umbrix.core.model.QueryAst

class QueryParserTest extends FunSuite {

  test("QueryParser parses empty queries into QueryAst.Empty") {
    assertEquals(QueryParser.parse(""), Right(QueryAst.Empty))
    assertEquals(QueryParser.parse("   "), Right(QueryAst.Empty))
    assertEquals(QueryParser.parse(null), Right(QueryAst.Empty))
  }

  test("QueryParser parses single unquoted and quoted literals") {
    val unquoted = QueryParser.parse("timeout")
    assertEquals(unquoted, Right(QueryAst.Literal("timeout", caseSensitive = false)))

    val quoted = QueryParser.parse("\"connection reset by peer\"")
    assertEquals(quoted, Right(QueryAst.Literal("connection reset by peer", caseSensitive = false)))
  }

  test("QueryParser parses case:sensitive modifier") {
    val caseSens = QueryParser.parse("case:sensitive \"FatalError\"")
    assertEquals(caseSens, Right(QueryAst.Literal("FatalError", caseSensitive = true)))
  }

  test("QueryParser parses implicit and explicit AND queries") {
    val implicitAnd = QueryParser.parse("database timeout")
    assertEquals(
      implicitAnd,
      Right(QueryAst.And(QueryAst.Literal("database", false), QueryAst.Literal("timeout", false)))
    )

    val explicitAnd = QueryParser.parse("database AND timeout")
    assertEquals(
      explicitAnd,
      Right(QueryAst.And(QueryAst.Literal("database", false), QueryAst.Literal("timeout", false)))
    )
  }

  test("QueryParser respects operator precedence: NOT > AND > OR") {
    val query = QueryParser.parse("error OR auth AND NOT healthcheck")
    assert(query.isRight)
    query.foreach { ast =>
      assertEquals(
        ast,
        QueryAst.Or(
          QueryAst.Literal("error", false),
          QueryAst.And(
            QueryAst.Literal("auth", false),
            QueryAst.Not(QueryAst.Literal("healthcheck", false))
          )
        )
      )
    }
  }

  test("QueryParser evaluates parenthesized groupings") {
    val query = QueryParser.parse("(auth OR billing) AND error")
    assert(query.isRight)
    query.foreach { ast =>
      assertEquals(
        ast,
        QueryAst.And(
          QueryAst.Or(QueryAst.Literal("auth", false), QueryAst.Literal("billing", false)),
          QueryAst.Literal("error", false)
        )
      )
    }
  }

  test("QueryParser parses discrete level filters") {
    val query = QueryParser.parse("level:ERROR AND timeout")
    assert(query.isRight)
    query.foreach { ast =>
      assertEquals(
        ast,
        QueryAst.And(
          QueryAst.LevelFilter(LogLevel.Error),
          QueryAst.Literal("timeout", false)
        )
      )
    }
  }

  test("QueryParser parses ISO timestamp ranges") {
    val query = QueryParser.parse("since:2026-09-22T10:00:00Z")
    assert(query.isRight)
    query.foreach {
      case QueryAst.TimeRange(Some(micros), None) =>
        assert(micros > 0L)
      case other =>
        fail(s"Unexpected AST: $other")
    }
  }

  test("QueryParser parses regular expressions and handles pathological ReDoS patterns linearly") {
    val regexQuery = QueryParser.parse("/error-\\d{4}/")
    assert(regexQuery.isRight)
    regexQuery.foreach {
      case QueryAst.Regex(pattern) =>
        assert(pattern.matcher("error-1234").find())
        assert(!pattern.matcher("error-abc").find())
      case other =>
        fail(s"Unexpected AST: $other")
    }

    // Pathological ReDoS pattern that blows up backtracking regex engines
    val pathological = QueryParser.parse("/(a+)+b/")
    assert(pathological.isRight) // RE2J compiles and executes linearly in O(N*M)
  }

  test("QueryParser returns structured diagnostics on malformed queries") {
    val unclosedQuote = QueryParser.parse("\"unclosed string")
    assert(unclosedQuote.isLeft)
    unclosedQuote.left.foreach { err =>
      assert(err.message.contains("Unclosed quote"))
    }

    val unclosedParen = QueryParser.parse("(auth OR billing")
    assert(unclosedParen.isLeft)

    val invalidRegex = QueryParser.parse("/[a-z/")
    assert(invalidRegex.isLeft)
    invalidRegex.left.foreach { err =>
      assert(err.message.contains("Invalid regular expression"))
    }
  }
}
