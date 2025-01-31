package wasm.ir2wasm

import wasm.wasm4s._

import org.scalajs.ir.{Trees => IRTrees}
import org.scalajs.ir.{Types => IRTypes}
import org.scalajs.ir.{Names => IRNames}
import org.scalajs.ir.ClassKind
import org.scalajs.ir.Traversers

import org.scalajs.linker.standard.LinkedClass

import WasmContext._

object Preprocessor {
  def preprocess(classes: List[LinkedClass])(implicit ctx: WasmContext): Unit = {
    for (clazz <- classes)
      preprocess(clazz)

    for (clazz <- classes)
      collectAbstractMethodCalls(clazz)
  }

  private def preprocess(clazz: LinkedClass)(implicit ctx: WasmContext): Unit = {
    val infos = clazz.methods.filterNot(_.flags.namespace.isConstructor).map { method =>
      makeWasmFunctionInfo(clazz, method)
    }

    ctx.putClassInfo(
      clazz.name.name,
      new WasmClassInfo(
        clazz.name.name,
        clazz.kind,
        infos,
        clazz.fields.collect { case f: IRTrees.FieldDef => Names.WasmFieldName(f.name.name) },
        clazz.superClass.map(_.name),
        clazz.interfaces.map(_.name),
        clazz.ancestors,
        clazz.jsNativeLoadSpec,
        clazz.jsNativeMembers.map(m => m.name.name -> m.jsNativeLoadSpec).toMap
      )
    )
  }

  private def makeWasmFunctionInfo(
      clazz: LinkedClass,
      method: IRTrees.MethodDef
  ): WasmFunctionInfo = {
    WasmFunctionInfo(
      Names.WasmFunctionName(clazz.name.name, method.name.name),
      method.args.map(_.ptpe),
      method.resultType,
      isAbstract = method.body.isEmpty
    )
  }

  /** Collect WasmFunctionInfo based on the abstract method call
    *
    * ```
    * class A extends B:
    *   def a = 1
    *
    * class B extends C:
    *   def b: Int = 1
    *   override def c: Int = 1
    *
    * abstract class C:
    *   def c: Int
    * ```
    *
    * why we need this? - The problem is that the frontend linker gets rid of abstract method
    * entirely.
    *
    * It keeps B.c because it's concrete and used. But because `C.c` isn't there at all anymore, if
    * we have val `x: C` and we call `x.c`, we don't find the method at all.
    */
  private def collectAbstractMethodCalls(clazz: LinkedClass)(implicit ctx: WasmContext): Unit = {
    object traverser extends Traversers.Traverser {
      import IRTrees._

      override def traverse(tree: Tree): Unit = {
        super.traverse(tree)

        tree match {
          case Apply(flags, receiver, methodName, _) =>
            receiver.tpe match {
              case IRTypes.ClassType(className) =>
                val classInfo = ctx.getClassInfo(className)
                classInfo.maybeAddAbstractMethod(methodName.name, ctx)
              case _ =>
                ()
            }

          case _ =>
            ()
        }
      }
    }

    for (method <- clazz.methods)
      traverser.traverseMethodDef(method)
    for (export <- clazz.exportedMembers)
      traverser.traverseJSMethodPropDef(export)
  }
}
