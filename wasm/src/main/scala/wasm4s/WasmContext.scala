package wasm.wasm4s

import scala.collection.mutable

import Names._
import Names.WasmTypeName._
import Types._

import org.scalajs.ir.{Names => IRNames}
import org.scalajs.ir.{Types => IRTypes}
import org.scalajs.ir.{Trees => IRTrees}
import org.scalajs.ir.ClassKind

import scala.collection.mutable.LinkedHashMap
import wasm.ir2wasm.TypeTransformer

import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.linker.interface.unstable.ModuleInitializerImpl

trait ReadOnlyWasmContext {
  import WasmContext._
  protected val gcTypes = new WasmSymbolTable[WasmTypeName, WasmGCTypeDefinition]()
  protected val functions = new WasmSymbolTable[WasmFunctionName, WasmFunction]()
  protected val globals = new WasmSymbolTable[WasmGlobalName, WasmGlobal]()

  protected val classInfo = mutable.Map[IRNames.ClassName, WasmClassInfo]()
  private val vtablesCache = mutable.Map[IRNames.ClassName, WasmVTable]()
  private val itablesCache = mutable.Map[IRNames.ClassName, WasmClassItables]()

  def getClassInfo(name: IRNames.ClassName): WasmClassInfo =
    classInfo.getOrElse(name, throw new Error(s"Class not found: $name"))

  def inferTypeFromTypeRef(typeRef: IRTypes.TypeRef): IRTypes.Type = typeRef match {
    case IRTypes.PrimRef(tpe) =>
      tpe
    case IRTypes.ClassRef(className) =>
      if (className == IRNames.ObjectClass) IRTypes.AnyType
      else IRTypes.ClassType(className)
    case typeRef: IRTypes.ArrayTypeRef =>
      IRTypes.ArrayType(typeRef)
  }

  /** Collects all methods declared, inherited, and mixed-in by the given class, super-class, and
    * interfaces.
    *
    * @param className
    *   class to collect methods from
    * @param includeAbstractMethods
    *   whether to include abstract methods
    * @return
    *   list of methods in order that "collectMethods(superClass) ++ methods from interfaces ++
    *   methods from the class"
    */
  private def collectMethods(
      className: IRNames.ClassName,
      includeAbstractMethods: Boolean
  ): List[WasmFunctionInfo] = {
    val info = classInfo.getOrElse(className, throw new Error(s"Class not found: $className"))
    val fromSuperClass =
      info.superClass.map(collectMethods(_, includeAbstractMethods)).getOrElse(Nil)
    val fromInterfaces = info.interfaces.flatMap(collectMethods(_, includeAbstractMethods))
    fromSuperClass ++ fromInterfaces ++
      (if (includeAbstractMethods) info.methods
       else info.methods.filterNot(_.isAbstract))
  }

  private def calculateVtable(
      name: IRNames.ClassName,
      includeAbstractMethods: Boolean
  ): List[WasmFunctionInfo] = {
    collectMethods(name, includeAbstractMethods)
      .foldLeft(Array.empty[WasmFunctionInfo]) { case (acc, m) =>
        acc.indexWhere(_.name.methodName == m.name.methodName) match {
          case i if i < 0 => acc :+ m
          case i          => acc.updated(i, m)
        }
      }
      .toList
  }

  def calculateGlobalVTable(name: IRNames.ClassName): List[WasmFunctionInfo] =
    // Do not include abstract methods when calculating vtable instance,
    // all slots should be filled with the function reference to the concrete methods
    calculateVtable(name, includeAbstractMethods = false)

  def calculateVtableType(name: IRNames.ClassName): WasmVTable = {
    vtablesCache.getOrElseUpdate(
      name, {
        val functions = calculateVtable(name, includeAbstractMethods = true)
        WasmVTable(functions)
      }
    )
  }

  def calculateClassItables(clazz: IRNames.ClassName): WasmClassItables = {
    def collectInterfaces(info: WasmClassInfo): List[WasmClassInfo] = {
      val superInterfaces =
        info.superClass.map(s => collectInterfaces(getClassInfo(s))).getOrElse(Nil)
      val ifaces = info.interfaces.flatMap { iface =>
        collectInterfaces(getClassInfo(iface))
      }

      if (info.isInterface) superInterfaces ++ ifaces :+ info
      else superInterfaces ++ ifaces
    }

    itablesCache.getOrElseUpdate(clazz, WasmClassItables(collectInterfaces(getClassInfo(clazz))))
  }
}

trait FunctionTypeWriterWasmContext extends ReadOnlyWasmContext { this: WasmContext =>
  protected val functionSignatures = LinkedHashMap.empty[WasmFunctionSignature, Int]
  protected val constantStringGlobals = LinkedHashMap.empty[String, WasmGlobalName]

  private var nextConstantStringIndex: Int = 1

  protected def addGlobal(g: WasmGlobal): Unit

  def addFunctionType(sig: WasmFunctionSignature): WasmFunctionTypeName = {
    functionSignatures.get(sig) match {
      case None =>
        val idx = functionSignatures.size
        functionSignatures.update(sig, idx)
        val typeName = WasmFunctionTypeName(idx)
        val ty = WasmFunctionType(typeName, sig)
        module.addFunctionType(ty)
        typeName
      case Some(value) => WasmFunctionTypeName(value)
    }
  }

  def addConstantStringGlobal(str: String): WasmGlobalName = {
    constantStringGlobals.get(str) match {
      case Some(globalName) =>
        globalName

      case None =>
        val globalName = WasmGlobalName.WasmGlobalConstantStringName(nextConstantStringIndex)
        constantStringGlobals(str) = globalName

        /* We need an initial value of type (ref any), which is also a constant
         * expression. It is not that easy to come up with such a value that
         * does not need to reference other things right away.
         * We use an `ref.i31 (i32.const 0)` as a trick.
         * The real value will be filled in during initialization of the module
         * in the Start section.
         */
        val initValue = WasmExpr(List(WasmInstr.I32_CONST(WasmImmediate.I32(0)), WasmInstr.REF_I31))

        addGlobal(WasmGlobal(globalName, Types.WasmRefType.any, initValue, isMutable = true))
        nextConstantStringIndex += 1
        globalName
    }
  }
}

class WasmContext(val module: WasmModule) extends FunctionTypeWriterWasmContext {
  import WasmContext._

  private val _startInstructions: mutable.ListBuffer[WasmInstr] = new mutable.ListBuffer()

  def addExport(exprt: WasmExport[_]): Unit = module.addExport(exprt)
  def addFunction(fun: WasmFunction): Unit = {
    module.addFunction(fun)
    functions.define(fun)
  }
  def addGCType(ty: WasmStructType): Unit = {
    module.addRecGroupType(ty)
    gcTypes.define(ty)
  }
  def addGlobal(g: WasmGlobal): Unit = {
    module.addGlobal(g)
    globals.define(g)
  }

  def putClassInfo(name: IRNames.ClassName, info: WasmClassInfo): Unit =
    classInfo.put(name, info)

  private def addHelperImport(
      name: WasmFunctionName,
      params: List[WasmType],
      results: List[WasmType]
  ): Unit = {
    val sig = WasmFunctionSignature(params, results)
    val typ = WasmFunctionType(addFunctionType(sig), sig)
    module.addImport(WasmImport(name.className, name.methodName, WasmImportDesc.Func(name, typ)))
  }

  addHelperImport(WasmFunctionName.is, List(WasmAnyRef, WasmAnyRef), List(WasmInt32))

  addHelperImport(WasmFunctionName.undef, List(), List(WasmRefType.any))
  addHelperImport(WasmFunctionName.isUndef, List(WasmAnyRef), List(WasmInt32))

  locally {
    import IRTypes._
    for (primRef <- List(BooleanRef, ByteRef, ShortRef, IntRef, FloatRef, DoubleRef)) {
      val wasmType = primRef match {
        case FloatRef  => WasmFloat32
        case DoubleRef => WasmFloat64
        case _         => WasmInt32
      }
      addHelperImport(WasmFunctionName.box(primRef), List(wasmType), List(WasmAnyRef))
      addHelperImport(WasmFunctionName.unbox(primRef), List(WasmAnyRef), List(wasmType))
      addHelperImport(WasmFunctionName.unboxOrNull(primRef), List(WasmAnyRef), List(WasmAnyRef))
      addHelperImport(WasmFunctionName.typeTest(primRef), List(WasmAnyRef), List(WasmInt32))
    }
  }

  addHelperImport(WasmFunctionName.emptyString, List(), List(WasmRefType.any))
  addHelperImport(WasmFunctionName.stringLength, List(WasmRefType.any), List(WasmInt32))
  addHelperImport(WasmFunctionName.stringCharAt, List(WasmRefType.any, WasmInt32), List(WasmInt32))
  addHelperImport(WasmFunctionName.jsValueToString, List(WasmAnyRef), List(WasmRefType.any))
  addHelperImport(WasmFunctionName.booleanToString, List(WasmInt32), List(WasmRefType.any))
  addHelperImport(WasmFunctionName.charToString, List(WasmInt32), List(WasmRefType.any))
  addHelperImport(WasmFunctionName.intToString, List(WasmInt32), List(WasmRefType.any))
  addHelperImport(WasmFunctionName.longToString, List(WasmInt64), List(WasmRefType.any))
  addHelperImport(WasmFunctionName.doubleToString, List(WasmFloat64), List(WasmRefType.any))
  addHelperImport(
    WasmFunctionName.stringConcat,
    List(WasmRefType.any, WasmRefType.any),
    List(WasmRefType.any)
  )
  addHelperImport(WasmFunctionName.isString, List(WasmAnyRef), List(WasmInt32))

  addHelperImport(WasmFunctionName.jsValueHashCode, List(WasmRefType.any), List(WasmInt32))

  addHelperImport(WasmFunctionName.jsGlobalRefGet, List(WasmRefType.any), List(WasmAnyRef))
  addHelperImport(WasmFunctionName.jsGlobalRefSet, List(WasmRefType.any, WasmAnyRef), Nil)
  addHelperImport(WasmFunctionName.jsGlobalRefTypeof, List(WasmRefType.any), List(WasmRefType.any))
  addHelperImport(WasmFunctionName.jsNewArray, Nil, List(WasmAnyRef))
  addHelperImport(WasmFunctionName.jsArrayPush, List(WasmAnyRef, WasmAnyRef), List(WasmAnyRef))
  addHelperImport(
    WasmFunctionName.jsArraySpreadPush,
    List(WasmAnyRef, WasmAnyRef),
    List(WasmAnyRef)
  )
  addHelperImport(WasmFunctionName.jsNewObject, Nil, List(WasmAnyRef))
  addHelperImport(
    WasmFunctionName.jsObjectPush,
    List(WasmAnyRef, WasmAnyRef, WasmAnyRef),
    List(WasmAnyRef)
  )
  addHelperImport(WasmFunctionName.jsSelect, List(WasmAnyRef, WasmAnyRef), List(WasmAnyRef))
  addHelperImport(WasmFunctionName.jsSelectSet, List(WasmAnyRef, WasmAnyRef, WasmAnyRef), Nil)
  addHelperImport(WasmFunctionName.jsNew, List(WasmAnyRef, WasmAnyRef), List(WasmAnyRef))
  addHelperImport(WasmFunctionName.jsFunctionApply, List(WasmAnyRef, WasmAnyRef), List(WasmAnyRef))
  addHelperImport(
    WasmFunctionName.jsMethodApply,
    List(WasmAnyRef, WasmAnyRef, WasmAnyRef),
    List(WasmAnyRef)
  )
  addHelperImport(WasmFunctionName.jsDelete, List(WasmAnyRef, WasmAnyRef), Nil)
  addHelperImport(WasmFunctionName.jsIsTruthy, List(WasmAnyRef), List(WasmInt32))
  addHelperImport(WasmFunctionName.jsLinkingInfo, Nil, List(WasmAnyRef))

  for ((op, name) <- WasmFunctionName.jsUnaryOps)
    addHelperImport(name, List(WasmAnyRef), List(WasmAnyRef))

  for ((op, name) <- WasmFunctionName.jsBinaryOps) {
    val resultType =
      if (op == IRTrees.JSBinaryOp.=== || op == IRTrees.JSBinaryOp.!==) WasmInt32
      else WasmAnyRef
    addHelperImport(name, List(WasmAnyRef, WasmAnyRef), List(resultType))
  }

  def addStartInstructions(instrs: List[WasmInstr]): Unit =
    _startInstructions ++= instrs

  def complete(moduleInitializers: List[ModuleInitializer]): Unit = {
    val instrs = _startInstructions

    for ((str, globalName) <- constantStringGlobals) {
      instrs += WasmInstr.CALL(WasmImmediate.FuncIdx(WasmFunctionName.emptyString))
      for (c <- str) {
        instrs += WasmInstr.I32_CONST(WasmImmediate.I32(c.toInt))
        instrs += WasmInstr.CALL(WasmImmediate.FuncIdx(WasmFunctionName.charToString))
        instrs += WasmInstr.CALL(WasmImmediate.FuncIdx(WasmFunctionName.stringConcat))
      }
      instrs += WasmInstr.GLOBAL_SET(WasmImmediate.GlobalIdx(globalName))
    }
    moduleInitializers.foreach { init =>
      ModuleInitializerImpl.fromInitializer(init.initializer) match {
        case ModuleInitializerImpl.MainMethodWithArgs(className, encodedMainMethodName, args) =>
          () // TODO: but we don't use args yet in scala-wasm
        case ModuleInitializerImpl.VoidMainMethod(className, encodedMainMethodName) =>
          val name = className.withSuffix("$")
          instrs +=
            WasmInstr.CALL(WasmImmediate.FuncIdx(Names.WasmFunctionName.loadModule(name)))
          instrs += WasmInstr.REF_AS_NOT_NULL
          instrs +=
            WasmInstr.CALL(
              WasmImmediate.FuncIdx(WasmFunctionName(name, encodedMainMethodName))
            )
      }
    }

    if (_startInstructions.nonEmpty) {
      val sig = WasmFunctionSignature(Nil, Nil)
      val funTypeName = addFunctionType(sig)
      val startFunction = WasmFunction(
        WasmFunctionName.start,
        WasmFunctionType(funTypeName, sig),
        Nil,
        WasmExpr(_startInstructions.toList)
      )
      addFunction(startFunction)
      module.setStartFunction(WasmFunctionName.start)
    }
  }
}

object WasmContext {
  private val classFieldOffset = 2 // vtable, itables

  private val AncestorsOfHijackedClasses: Set[IRNames.ClassName] = {
    // We hard-code this for now, but ideally we should derive it
    IRNames.HijackedClasses ++
      Set(
        IRNames.ObjectClass,
        IRNames.SerializableClass,
        IRNames.ClassName("java.lang.CharSequence"),
        IRNames.ClassName("java.lang.Comparable"),
        IRNames.ClassName("java.lang.Number"),
        IRNames.ClassName("java.lang.constant.Constable"),
        IRNames.ClassName("java.lang.constant.ConstantDesc")
      )
  }

  final class WasmClassInfo(
      val name: IRNames.ClassName,
      val kind: ClassKind,
      private var _methods: List[WasmFunctionInfo],
      private val fields: List[WasmFieldName],
      val superClass: Option[IRNames.ClassName],
      val interfaces: List[IRNames.ClassName],
      val ancestors: List[IRNames.ClassName],
      val jsNativeLoadSpec: Option[IRTrees.JSNativeLoadSpec],
      val jsNativeMembers: Map[IRNames.MethodName, IRTrees.JSNativeLoadSpec]
  ) {
    def isAncestorOfHijackedClass: Boolean = AncestorsOfHijackedClasses.contains(name)

    def isInterface = kind == ClassKind.Interface

    def methods: List[WasmFunctionInfo] = _methods

    def maybeAddAbstractMethod(methodName: IRNames.MethodName, ctx: WasmContext): Unit = {
      if (!methods.exists(_.name.methodName == methodName.nameString)) {
        val wasmName = WasmFunctionName(name, methodName)
        val argTypes = methodName.paramTypeRefs.map(ctx.inferTypeFromTypeRef(_))
        val resultType = ctx.inferTypeFromTypeRef(methodName.resultTypeRef)
        _methods = _methods :+ WasmFunctionInfo(wasmName, argTypes, resultType, isAbstract = true)
      }
    }

    def getMethodInfo(methodName: IRNames.MethodName): WasmFunctionInfo = {
      methods.find(_.name.methodName == methodName.nameString).getOrElse {
        throw new IllegalArgumentException(
          s"Cannot find method ${methodName.nameString} in class ${name.nameString}"
        )
      }
    }

    def getFieldIdx(name: WasmFieldName): WasmImmediate.StructFieldIdx =
      fields.indexWhere(_ == name) match {
        case i if i < 0 => throw new Error(s"Field not found: $name")
        case i          => WasmImmediate.StructFieldIdx(i + classFieldOffset)
      }
  }

  case class WasmFunctionInfo(
      name: WasmFunctionName,
      argTypes: List[IRTypes.Type],
      resultType: IRTypes.Type,
      // flags: IRTrees.MemberFlags,
      isAbstract: Boolean
  ) {
    def toWasmFunctionType()(implicit ctx: FunctionTypeWriterWasmContext): WasmFunctionType =
      TypeTransformer.transformFunctionType(this)

  }
  case class WasmFieldInfo(name: WasmFieldName, tpe: Types.WasmType)

  /** itables in order that super class's interface -> interfaces
    */
  case class WasmClassItables(val itables: List[WasmClassInfo]) {
    def isEmpty = itables.isEmpty
    // def resolveWithIdx(name: IRNames.ClassName): (Int, WasmClassInfo) = {
    //   val idx = itables.indexWhere(_.name == name)
    //   if (idx < 0) throw new Error(s"itable not found: $name")
    //   else (idx, itables(idx))
    // }
    /** @param name
      *   method name to find
      * @return
      *   (itableIdx, methodIdx) where itableIdx is the index of the interface in itables and
      *   methodIdx is the index of the method in that
      */
    def resolveMethod(name: IRNames.MethodName): (Int, Int) = {
      var foundMethodIdx = -1
      val itableIdx =
        itables.lastIndexWhere { classInfo =>
          val methodIdx = classInfo.methods.lastIndexWhere { func =>
            func.name.methodName == name.nameString
          }
          if (methodIdx >= 0) {
            foundMethodIdx = methodIdx
            true
          } else false
        }
      if (itableIdx >= 0)
        (itableIdx, foundMethodIdx)
      else throw new Error(s"Method not found: $name")
    }
  }
  case class WasmVTable(val functions: List[WasmFunctionInfo]) {
    def resolve(name: WasmFunctionName): WasmFunctionInfo =
      functions
        .find(_.name.methodName == name.methodName)
        .getOrElse(throw new Error(s"Function not found: $name"))
    def resolveWithIdx(name: WasmFunctionName): (Int, WasmFunctionInfo) = {
      val idx = functions.indexWhere(_.name.methodName == name.methodName)
      if (idx < 0)
        throw new Error(s"Function not found: $name among ${functions.map(_.name.methodName)}")
      else (idx, functions(idx))
    }
  }
}
