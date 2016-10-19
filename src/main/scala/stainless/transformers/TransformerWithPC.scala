/* Copyright 2009-2016 EPFL, Lausanne */

package stainless
package transformers

trait TransformerWithPC extends inox.transformers.TransformerWithPC {
  val program: Program
  import program.trees._
  import program.symbols._

  override protected def rec(e: Expr, path: Path): Expr = e match {
    case Ensuring(req @ Require(pre, body), l @ Lambda(Seq(vd), post)) =>
      val spre = rec(pre, path)
      val sbody = rec(body, path withCond spre)
      val spost = rec(post, path withCond spre withBinding (vd -> sbody))
      Ensuring(
        Require(spre, sbody).copiedFrom(req),
        Lambda(Seq(vd), spost).copiedFrom(l)
      ).copiedFrom(e)

    case Ensuring(body, l @ Lambda(Seq(vd), post)) =>
      val sbody = rec(body, path)
      val spost = rec(post, path withBinding (vd -> sbody))
      Ensuring(sbody, Lambda(Seq(vd), spost).copiedFrom(l)).copiedFrom(e)

    case Require(pre, body) =>
      val spre = rec(pre, path)
      val sbody = rec(body, path withCond spre)
      Require(spre, sbody).copiedFrom(e)

    case MatchExpr(scrut, cases) =>
      val rs = rec(scrut, path)

      var soFar = path

      MatchExpr(rs, cases.map { c =>
        val patternPathPos = conditionForPattern(rs, c.pattern, includeBinders = true)
        val patternPathNeg = conditionForPattern(rs, c.pattern, includeBinders = false)

        val map = mapForPattern(rs, c.pattern)
        val sguard = c.optGuard.map(rec(_, soFar))
        val guardOrTrue = sguard.getOrElse(BooleanLiteral(true))
        val guardMapped = exprOps.replaceFromSymbols(map, guardOrTrue)

        val subPath = soFar merge (patternPathPos withCond guardOrTrue)
        soFar = soFar merge (patternPathNeg withCond guardMapped).negate

        MatchCase(c.pattern, sguard, rec(c.rhs, subPath)).copiedFrom(c)
      }).copiedFrom(e)

    case _ => super.rec(e, path)
  }
}
