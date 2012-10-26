package leon
package verification

import purescala.Common._
import purescala.Trees._
import purescala.TreeOps._
import purescala.Extractors._
import purescala.Definitions._

import scala.collection.mutable.{Map => MutableMap}

class DefaultTactic(reporter: Reporter) extends Tactic(reporter) {
    val description = "Default verification condition generation approach"
    override val shortDescription = "default"

    var _prog : Option[Program] = None
    def program : Program = _prog match {
      case None => throw new Exception("Program never set in DefaultTactic.")
      case Some(p) => p
    }

    override def setProgram(program: Program) : Unit = {
      _prog = Some(program)
    }

    def generatePostconditions(functionDefinition: FunDef) : Seq[VerificationCondition] = {
      assert(functionDefinition.body.isDefined)
      val prec = functionDefinition.precondition
      val post = functionDefinition.postcondition
      val body = matchToIfThenElse(functionDefinition.body.get)

      if(post.isEmpty) {
        Seq.empty
      } else {
        val theExpr = { 
          val resFresh = FreshIdentifier("result", true).setType(body.getType)
          val bodyAndPost = Let(resFresh, body, replace(Map(ResultVariable() -> Variable(resFresh)), matchToIfThenElse(post.get)))

          val withPrec = if(prec.isEmpty) {
            bodyAndPost
          } else {
            Implies(matchToIfThenElse(prec.get), bodyAndPost)
          }

          import Analysis._

          if(Settings.zeroInlining) {
            withPrec
          } else {
            if(Settings.experimental) {
              reporter.info("Raw:")
              reporter.info(withPrec)
              reporter.info("Raw, expanded:")
              reporter.info(expandLets(withPrec))
            }
            reporter.info(" - inlining...")
            val expr0 = inlineNonRecursiveFunctions(program, withPrec)
            if(Settings.experimental) {
              reporter.info("Inlined:")
              reporter.info(expr0)
              reporter.info("Inlined, expanded:")
              reporter.info(expandLets(expr0))
            }
            reporter.info(" - unrolling...")
            val expr1 = unrollRecursiveFunctions(program, expr0, Settings.unrollingLevel)
            if(Settings.experimental) {
              reporter.info("Unrolled:")
              reporter.info(expr1)
              reporter.info("Unrolled, expanded:")
              reporter.info(expandLets(expr1))
            }
            reporter.info(" - inlining contracts...")
            val expr2 = inlineContracts(expr1)
            if(Settings.experimental) {
              reporter.info("Contract'ed:")
              reporter.info(expr2)
              reporter.info("Contract'ed, expanded:")
              reporter.info(expandLets(expr2))
            }
            expr2
          }
        }
        if(functionDefinition.fromLoop)
          Seq(new VerificationCondition(theExpr, functionDefinition.parent.get, VCKind.InvariantPost, this.asInstanceOf[DefaultTactic]).setPosInfo(functionDefinition))
        else
          Seq(new VerificationCondition(theExpr, functionDefinition, VCKind.Postcondition, this.asInstanceOf[DefaultTactic]))
      }
    }
  
    def generatePreconditions(function: FunDef) : Seq[VerificationCondition] = {
      val toRet = if(function.hasBody) {
        val cleanBody = expandLets(matchToIfThenElse(function.body.get))

        val allPathConds = collectWithPathCondition((t => t match {
          case FunctionInvocation(fd, _) if(fd.hasPrecondition) => true
          case _ => false
        }), cleanBody)

        def withPrecIfDefined(path: Seq[Expr], shouldHold: Expr) : Expr = if(function.hasPrecondition) {
          Not(And(And(matchToIfThenElse(function.precondition.get) +: path), Not(shouldHold)))
        } else {
          Not(And(And(path), Not(shouldHold)))
        }

        allPathConds.map(pc => {
          val path : Seq[Expr] = pc._1
          val fi = pc._2.asInstanceOf[FunctionInvocation]
          val FunctionInvocation(fd, args) = fi
          val prec : Expr = freshenLocals(matchToIfThenElse(fd.precondition.get))
          val newLetIDs = fd.args.map(a => FreshIdentifier("arg_" + a.id.name, true).setType(a.tpe))
          val substMap = Map[Expr,Expr]((fd.args.map(_.toVariable) zip newLetIDs.map(Variable(_))) : _*)
          val newBody : Expr = replace(substMap, prec)
          val newCall : Expr = (newLetIDs zip args).foldRight(newBody)((iap, e) => Let(iap._1, iap._2, e))

          if(fd.fromLoop)
            new VerificationCondition(
              withPrecIfDefined(path, newCall),
              fd.parent.get,
              if(fd == function) VCKind.InvariantInd else VCKind.InvariantInit,
              this.asInstanceOf[DefaultTactic]).setPosInfo(fd)
          else
            new VerificationCondition(
              withPrecIfDefined(path, newCall),
              function,
              VCKind.Precondition,
              this.asInstanceOf[DefaultTactic]).setPosInfo(fi)
        }).toSeq
      } else {
        Seq.empty
      }

      // println("PRECS VCs FOR " + function.id.name)
      // println(toRet.toList.map(vc => vc.posInfo + " -- " + vc.condition).mkString("\n\n"))

      toRet
    }

    def generatePatternMatchingExhaustivenessChecks(function: FunDef) : Seq[VerificationCondition] = {
      val toRet = if(function.hasBody) {
        val cleanBody = matchToIfThenElse(function.body.get)

        val allPathConds = collectWithPathCondition((t => t match {
          case Error("non-exhaustive match") => true
          case _ => false
        }), cleanBody)
  
        def withPrecIfDefined(conds: Seq[Expr]) : Expr = if(function.hasPrecondition) {
          Not(And(matchToIfThenElse(function.precondition.get), And(conds)))
        } else {
          Not(And(conds))
        }

        allPathConds.map(pc => 
          new VerificationCondition(
            withPrecIfDefined(pc._1),
            if(function.fromLoop) function.parent.get else function,
            VCKind.ExhaustiveMatch,
            this.asInstanceOf[DefaultTactic]).setPosInfo(pc._2.asInstanceOf[Error])
        ).toSeq
      } else {
        Seq.empty
      }

      // println("MATCHING VCs FOR " + function.id.name)
      // println(toRet.toList.map(vc => vc.posInfo + " -- " + vc.condition).mkString("\n\n"))

      toRet
    }

    def generateMapAccessChecks(function: FunDef) : Seq[VerificationCondition] = {
      val toRet = if (function.hasBody) {
        val cleanBody = mapGetWithChecks(matchToIfThenElse(function.body.get))

        val allPathConds = collectWithPathCondition((t => t match {
          case Error("key not found for map access") => true
          case _ => false
        }), cleanBody)

        def withPrecIfDefined(conds: Seq[Expr]) : Expr = if (function.hasPrecondition) {
          Not(And(mapGetWithChecks(matchToIfThenElse(function.precondition.get)), And(conds)))
        } else {
          Not(And(conds))
        }

        allPathConds.map(pc =>
          new VerificationCondition(
            withPrecIfDefined(pc._1),
            if(function.fromLoop) function.parent.get else function,
            VCKind.MapAccess,
            this.asInstanceOf[DefaultTactic]).setPosInfo(pc._2.asInstanceOf[Error])
        ).toSeq
      } else {
        Seq.empty
      }

      toRet
    }

    def generateArrayAccessChecks(function: FunDef) : Seq[VerificationCondition] = {
      val toRet = if (function.hasBody) {
        val cleanBody = matchToIfThenElse(function.body.get)

        val allPathConds = collectWithPathCondition((t => t match {
          case Error("Index out of bound") => true
          case _ => false
        }), cleanBody)

        def withPrecIfDefined(conds: Seq[Expr]) : Expr = if (function.hasPrecondition) {
          Not(And(mapGetWithChecks(matchToIfThenElse(function.precondition.get)), And(conds)))
        } else {
          Not(And(conds))
        }

        allPathConds.map(pc =>
          new VerificationCondition(
            withPrecIfDefined(pc._1),
            if(function.fromLoop) function.parent.get else function,
            VCKind.ArrayAccess,
            this.asInstanceOf[DefaultTactic]).setPosInfo(pc._2.asInstanceOf[Error])
        ).toSeq
      } else {
        Seq.empty
      }

      toRet
    }

    def generateMiscCorrectnessConditions(function: FunDef) : Seq[VerificationCondition] = {
      generateMapAccessChecks(function)
    }

    // prec: there should be no lets and no pattern-matching in this expression
    def collectWithPathCondition(matcher: Expr=>Boolean, expression: Expr) : Set[(Seq[Expr],Expr)] = {
      var collected : Set[(Seq[Expr],Expr)] = Set.empty

      def rec(expr: Expr, path: List[Expr]) : Unit = {
        if(matcher(expr)) {
          collected = collected + ((path.reverse, expr))
        }

        expr match {
          case Let(i,e,b) => {
            rec(e, path)
            rec(b, Equals(Variable(i), e) :: path)
          }
          case IfExpr(cond, then, elze) => {
            rec(cond, path)
            rec(then, cond :: path)
            rec(elze, Not(cond) :: path)
          }
          case NAryOperator(args, _) => args.foreach(rec(_, path))
          case BinaryOperator(t1, t2, _) => rec(t1, path); rec(t2, path)
          case UnaryOperator(t, _) => rec(t, path)
          case t : Terminal => ;
          case _ => scala.sys.error("Unhandled tree in collectWithPathCondition : " + expr)
        }
      }

      rec(expression, Nil)
      collected
    }
}
