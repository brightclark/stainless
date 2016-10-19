/* Copyright 2009-2016 EPFL, Lausanne */

package stainless
package termination

import scala.collection.mutable.{Map => MutableMap, ListBuffer}

trait StructuralSize {

  val checker: TerminationChecker
  import checker.program.trees._
  import checker.program.symbols._
  import dsl._

  private val functions: ListBuffer[FunDef] = new ListBuffer[FunDef]
  def sizeFunctions: Seq[FunDef] = functions.toSeq

  /* Absolute value for BigInt type
   *
   * def absBigInt(x: BigInt): BigInt = if (x >= 0) x else -x
   */
  val integerAbs: TypedFunDef = mkFunDef(FreshIdentifier("integerAbs"))()(_ => (
    Seq("x" :: IntegerType), IntegerType, { case Seq(x) =>
      if_ (x >= E(BigInt(0))) {
        x
      } else_ {
        -x
      }
    })).typed
  functions += integerAbs.fd

  private val bvCache: MutableMap[BVType, TypedFunDef] = MutableMap.empty

  /* Negative absolute value for bitvector types
   *
   * To avoid -Integer.MIN_VALUE overflow, we use negative absolute value
   * for bitvector integers.
   *
   * def absBV(x: BV): BV = if (x >= 0) -x else x
   */
  def bvAbs(tpe: BVType): TypedFunDef = bvCache.getOrElseUpdate(tpe, {
    val tfd = mkFunDef(FreshIdentifier("bvAbs" + tpe.size))()(_ => (
      Seq("x" :: tpe), tpe, { case Seq(x) =>
        if_ (x >= E(0)) {
          -x
        } else_ {
          x
        }
      })).typed
    functions += tfd.fd
    tfd
  })

  private val bv2IntegerCache: MutableMap[BVType, TypedFunDef] = MutableMap.empty

  /* Absolute value for bitvector type into mathematical integers
   *
   * We use a recursive function here as the bv2int functionality provided
   * through SMT solvers is waaaaay too slow. Recursivity requires the
   * postcondition for verification efforts to succeed.
   *
   * def absBV(x: BV): BigInt = (if (x == 0) {
   *   BigInt(0)
   * } else if (x > 0) {
   *   1 + absBV(x - 1)
   * } else {
   *   1 + absBV(-(x + 1)) // avoids -Integer.MIN_VALUE overflow
   * }) ensuring (_ >= 0)
   */
  def bvAbs2Integer(tpe: BVType): TypedFunDef = bv2IntegerCache.getOrElseUpdate(tpe, {
    val funID = FreshIdentifier("intAbs2Integer")
    val tfd = mkFunDef(funID)()(_ => (
      Seq("x" :: Int32Type), IntegerType, { case Seq(x) =>
        Ensuring(if_ (x === E(0)) {
          E(BigInt(0))
        } else_ {
          if_ (x > E(0)) {
            E(BigInt(1)) + E(funID)()(x - E(1))
          } else_ {
            E(BigInt(1)) + E(funID)()(-(x + E(1)))
          }
        }, \("res" :: IntegerType)(res => res >= E(BigInt(0))))
      })).typed
    functions += tfd.fd
    tfd
  })

  private val fullCache: MutableMap[Type, Identifier] = MutableMap.empty

  /* Fully recursive size computation
   *
   * Computes (positive) size of leon types by summing up all sub-elements
   * accessible in the type definition.
   */
  def fullSize(expr: Expr): Expr = expr.getType match {
    case (adt: ADTType) =>
      val rootType = adt.getADT.root.toType
      val fid = fullCache.get(rootType) match {
        case Some(id) => id
        case None =>
          val id = FreshIdentifier("fullSize$" + adt.id.name)
          fullCache += rootType -> id

          // we want to reuse generic size functions for sub-types
          val definition = rootType.getADT.definition
          val tparams = definition.tparams.map(_.tp)

          val v = Variable(FreshIdentifier("x"), rootType)
          functions += new FunDef(
            id,
            definition.tparams,
            Seq(v.toVal),
            IntegerType,
            Ensuring(MatchExpr(v, (definition match {
              case sort: ADTSort => sort.constructors
              case cons: ADTConstructor => Seq(cons)
            }).map { cons =>
              val arguments = cons.fields.map(_.freshen)
              val argumentPatterns = arguments.map(vd => WildcardPattern(Some(vd)))
              val rhs = arguments.map(vd => fullSize(vd.toVariable)).foldLeft[Expr](IntegerLiteral(1))(_ + _)
              MatchCase(ADTPattern(None, rootType, argumentPatterns), None, rhs)
            }), \("res" :: IntegerType)(res => res >= E(BigInt(0)))),
            Set.empty
          )

          id
      }

      FunctionInvocation(fid, adt.tps, Seq(expr))

    case TupleType(argTypes) => argTypes.zipWithIndex.map({
      case (_, index) => fullSize(tupleSelect(expr, index + 1, true))
    }).foldLeft[Expr](IntegerLiteral(0))(_ + _)

    case IntegerType =>
      integerAbs.applied(Seq(expr))

    case bv @ BVType(_) =>
      bvAbs2Integer(bv).applied(Seq(expr))

    case _ => IntegerLiteral(0)
  }

  object ContainerType {
    def unapply(c: ADTType): Option[Seq[ValDef]] = c.getADT match {
      case tcons: TypedADTConstructor =>
        if (tcons.fields.exists(vd => isSubtypeOf(vd.tpe, tcons.root.toType))) None
        else if (tcons.sort.isDefined && tcons.sort.get.constructors.size > 1) None
        else Some(tcons.fields)
      case _ => None
    }
  }

  def flatTypesPowerset(tpe: Type): Set[Expr => Expr] = {
    def powerSetToFunSet(l: TraversableOnce[Expr => Expr]): Set[Expr => Expr] = {
      l.toSet.subsets.filter(_.nonEmpty).map{
        (reconss: Set[Expr => Expr]) => (e : Expr) =>
          tupleWrap(reconss.toSeq map { f => f(e) })
      }.toSet
    }

    powerSetToFunSet(flatTypes(tpe))
  }

  def flatTypes(tpe: Type): Set[Expr => Expr] = {
    def rec(tpe: Type): Set[Expr => Expr] = tpe match {
      case ContainerType(fields) =>
        fields.flatMap { case vd =>
          rec(vd.tpe).map(recons => (e: Expr) => recons(adtSelector(e, vd.id)))
        }.toSet
      case TupleType(tpes) =>
        tpes.indices.flatMap { case index =>
          rec(tpes(index)).map(recons => (e: Expr) => recons(tupleSelect(e, index + 1, true)))
        }.toSet
      case _ => Set((e: Expr) => e)
    }

    rec(tpe)
  }

  def lexicographicallySmaller(s1: Seq[Expr], s2: Seq[Expr], strict: Boolean, sizeOfOneExpr: Expr => Expr): Expr = {
    // Note: The Equal and LessThan ASTs work for both BigInt and Bitvector

    val sameSizeExprs = for ((arg1, arg2) <- s1 zip s2) yield Equals(sizeOfOneExpr(arg1), sizeOfOneExpr(arg2))

    val lessBecauseLessAtFirstDifferentPos =
      orJoin(for (firstDifferent <- 0 until scala.math.min(s1.length, s2.length)) yield and(
          andJoin(sameSizeExprs.take(firstDifferent)),
          LessThan(sizeOfOneExpr(s1(firstDifferent)), sizeOfOneExpr(s2(firstDifferent)))
      ))

    if (s1.length > s2.length || (s1.length == s2.length && !strict)) {
      or(andJoin(sameSizeExprs), lessBecauseLessAtFirstDifferentPos)
    } else {
      lessBecauseLessAtFirstDifferentPos
    }
  }
}
