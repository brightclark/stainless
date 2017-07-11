/* Copyright 2009-2016 EPFL, Lausanne */

package stainless
package frontends.dotc

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.core.Phases._
import dotty.tools.dotc.core.Contexts._

import scala.collection.mutable.ListBuffer

import extraction.xlang.{ trees => xt }
import frontend.{ CallBack }

class StainlessExtraction(inoxCtx: inox.Context, callback: CallBack) extends Phase {

  def phaseName: String = "stainless extraction"

  // Share the same symbols between several runs.
  // TODO can we share it even for a longer period? i.e. for --watch
  private val symbols = new SymbolsContext

  def run(implicit ctx: Context): Unit = {
    val extraction = new CodeExtraction(inoxCtx, symbols)
    import extraction.{ctx => _, _}
    import AuxiliaryExtractors._

    val unit = ctx.compilationUnit
    val tree = unit.tpdTree
    val (id, stats) = tree match {
      case pd @ PackageDef(refTree, lst) =>
        val id = lst.collectFirst { case PackageDef(ref, stats) => ref } match {
          case Some(ref) => extractRef(ref)
          case None => FreshIdentifier(unit.source.file.name.replaceFirst("[.][^.]+$", ""))
        }
        (id, pd.stats)
      case _ => outOfSubsetError(tree, "Unexpected unit body")
    }

    val (imports, unitClasses, unitFunctions, subs, classes, functions) = extraction.extractStatic(stats)
    assert(unitFunctions.isEmpty, "Packages shouldn't contain functions")

    val file = unit.source.file.absolute.path
    val isLibrary = Main.libraryFiles contains file
    val xtUnit = xt.UnitDef(id, imports, unitClasses, subs, !isLibrary)

    callback(file, xtUnit, classes, functions)
  }

}

