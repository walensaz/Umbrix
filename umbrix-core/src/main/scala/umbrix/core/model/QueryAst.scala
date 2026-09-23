package umbrix.core.model

import com.google.re2j.Pattern

/**
 * Pure Algebraic Data Type (ADT) representing the filter query expression tree.
 *
 * Enforces linear-time ReDoS immunity via RE2J Pattern.
 */
sealed trait QueryAst

object QueryAst {
  case class Literal(text: String, caseSensitive: Boolean = false) extends QueryAst
  case class Regex(pattern: Pattern) extends QueryAst
  case class LevelFilter(level: LogLevel) extends QueryAst
  case class TimeRange(sinceMicros: Option[Long] = None, untilMicros: Option[Long] = None) extends QueryAst
  case class And(left: QueryAst, right: QueryAst) extends QueryAst
  case class Or(left: QueryAst, right: QueryAst) extends QueryAst
  case class Not(expr: QueryAst) extends QueryAst
  case object Empty extends QueryAst
}
