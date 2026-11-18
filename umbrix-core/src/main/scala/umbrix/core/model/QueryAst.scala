package umbrix.core.model

import com.google.re2j.Pattern

/**
 * Pure Algebraic Data Type (ADT) representing the filter query expression tree.
 *
 * Enforces linear-time ReDoS immunity via RE2J Pattern.
 */
sealed trait QueryAst {
  def extractNeedles: List[String] = {
    this match {
      case QueryAst.Literal(text, _) =>
        val trimmedText: String = text.trim
        if (trimmedText.isEmpty) {
          Nil
        } else {
          List(trimmedText)
        }
      case QueryAst.Regex(pattern) =>
        List(pattern.pattern())
      case QueryAst.LevelFilter(level) =>
        List(level.toString.toUpperCase)
      case QueryAst.TimeRange(_, _) =>
        Nil
      case QueryAst.And(left, right) =>
        left.extractNeedles ++ right.extractNeedles
      case QueryAst.Or(left, right) =>
        left.extractNeedles ++ right.extractNeedles
      case QueryAst.Not(_) =>
        Nil
      case QueryAst.CustomFunction(_, _) =>
        Nil
      case QueryAst.Empty =>
        Nil
    }
  }

  def extractNeedlesJava: java.util.List[String] = {
    val resultList: java.util.ArrayList[String] = new java.util.ArrayList[String]()
    def collect(node: QueryAst): Unit = {
      node match {
        case QueryAst.Literal(text, _) =>
          val trimmedText: String = text.trim
          if (!trimmedText.isEmpty) {
            resultList.add(trimmedText)
          }
        case QueryAst.Regex(pattern) =>
          resultList.add(pattern.pattern())
        case QueryAst.LevelFilter(level) =>
          resultList.add(level.toString.toUpperCase)
        case QueryAst.TimeRange(_, _) =>
          ()
        case QueryAst.And(left, right) =>
          collect(left)
          collect(right)
        case QueryAst.Or(left, right) =>
          collect(left)
          collect(right)
        case QueryAst.Not(_) =>
          ()
        case QueryAst.CustomFunction(_, _) =>
          ()
        case QueryAst.Empty =>
          ()
      }
    }
    collect(this)
    java.util.Collections.unmodifiableList(resultList)
  }
}

object QueryAst {
  case class Literal(text: String, caseSensitive: Boolean = false) extends QueryAst
  case class Regex(pattern: Pattern) extends QueryAst
  case class LevelFilter(level: LogLevel) extends QueryAst
  case class TimeRange(sinceMicros: Option[Long] = None, untilMicros: Option[Long] = None) extends QueryAst
  case class CustomFunction(functionName: String, arguments: List[String]) extends QueryAst
  case class And(left: QueryAst, right: QueryAst) extends QueryAst
  case class Or(left: QueryAst, right: QueryAst) extends QueryAst
  case class Not(expr: QueryAst) extends QueryAst
  case object Empty extends QueryAst
}
