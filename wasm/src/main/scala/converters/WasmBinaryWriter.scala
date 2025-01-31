package wasm
package converters

import scala.annotation.tailrec

import java.io.OutputStream
import java.io.ByteArrayOutputStream

import wasm.wasm4s._
import wasm.wasm4s.Names._
import wasm.wasm4s.Types._
import wasm.wasm4s.Types.WasmHeapType.Type
import wasm.wasm4s.Types.WasmHeapType.Func
import wasm.wasm4s.Types.WasmHeapType.Simple
import wasm.wasm4s.WasmInstr.END

final class WasmBinaryWriter(module: WasmModule) {
  import WasmBinaryWriter._

  private val allTypeDefinitions: List[WasmTypeDefinition[_ <: WasmTypeName]] = {
    module.recGroupTypes :::
      module.functionTypes :::
      module.arrayTypes
  }

  private val typeIdxValues: Map[WasmTypeName, Int] =
    allTypeDefinitions.map(_.name).zipWithIndex.toMap

  private val funcIdxValues: Map[WasmFunctionName, Int] = {
    val importedFunctionNames = module.imports.collect {
      case WasmImport(_, _, WasmImportDesc.Func(id, _)) => id
    }
    val allNames = importedFunctionNames ::: module.definedFunctions.map(_.name)
    allNames.zipWithIndex.toMap
  }

  private val globalIdxValues: Map[WasmGlobalName, Int] =
    module.globals.map(_.name).zipWithIndex.toMap

  private var localIdxValues: Option[Map[WasmLocalName, Int]] = None
  private var labelsInScope: List[Option[WasmImmediate.LabelIdx]] = Nil

  private def withLocalIdxValues(values: Map[WasmLocalName, Int])(f: => Unit): Unit = {
    val saved = localIdxValues
    localIdxValues = Some(values)
    try f
    finally localIdxValues = saved
  }

  def write(): Array[Byte] = {
    val fullOutput = new Buffer()

    // magic header: null char + "asm"
    fullOutput.byte(0)
    fullOutput.byte('a')
    fullOutput.byte('s')
    fullOutput.byte('m')

    // version
    fullOutput.byte(1)
    fullOutput.byte(0)
    fullOutput.byte(0)
    fullOutput.byte(0)

    writeSection(fullOutput, SectionType)(writeTypeSection(_))
    writeSection(fullOutput, SectionImport)(writeImportSection(_))
    writeSection(fullOutput, SectionFunction)(writeFunctionSection(_))
    writeSection(fullOutput, SectionGlobal)(writeGlobalSection(_))
    writeSection(fullOutput, SectionExport)(writeExportSection(_))
    if (module.startFunction.isDefined)
      writeSection(fullOutput, SectionStart)(writeStartSection(_))
    writeSection(fullOutput, SectionCode)(writeCodeSection(_))

    fullOutput.result()
  }

  private def writeSection(fullOutput: Buffer, sectionID: Byte)(f: Buffer => Unit): Unit = {
    fullOutput.byte(sectionID)
    fullOutput.byteLengthSubSection(f)
  }

  private def writeTypeSection(buf: Buffer): Unit = {
    buf.u32(1) // a single `rectype`
    buf.byte(0x4E) // `rectype` tag
    buf.u32(typeIdxValues.size) // number of `subtype`s in our single `rectype`

    def writeFieldType(field: WasmStructField): Unit = {
      writeType(buf, field.typ)
      buf.boolean(field.isMutable)
    }

    for (typeDef <- allTypeDefinitions) {
      typeDef match {
        case WasmArrayType(name, field) =>
          buf.byte(0x5E) // array
          writeFieldType(field)
        case WasmStructType(name, fields, superType) =>
          buf.byte(0x50) // sub
          buf.opt(superType)(writeTypeIdx(buf, _))
          buf.byte(0x5F) // struct
          buf.vec(fields)(writeFieldType(_))
        case WasmFunctionType(name, params, results) =>
          buf.byte(0x60) // func
          writeResultType(buf, params)
          writeResultType(buf, results)
      }
    }
  }

  private def writeImportSection(buf: Buffer): Unit = {
    buf.vec(module.imports) { imprt =>
      buf.name(imprt.module)
      buf.name(imprt.name)

      imprt.desc match {
        case WasmImportDesc.Func(id, typ) =>
          buf.byte(0x00) // func
          writeTypeIdx(buf, typ.name)
      }
    }
  }

  private def writeFunctionSection(buf: Buffer): Unit = {
    buf.vec(module.definedFunctions) { fun =>
      writeTypeIdx(buf, fun.typ.name)
    }
  }

  private def writeGlobalSection(buf: Buffer): Unit = {
    buf.vec(module.globals) { global =>
      writeType(buf, global.typ)
      buf.boolean(global.isMutable)
      writeExpr(buf, global.init)
    }
  }

  private def writeExportSection(buf: Buffer): Unit = {
    buf.vec(module.exports) { exp =>
      buf.name(exp.name)
      exp match {
        case exp: WasmExport.Function =>
          buf.byte(0x00)
          writeFuncIdx(buf, exp.field.name)
        case exp: WasmExport.Global =>
          buf.byte(0x03)
          writeGlobalIdx(buf, exp.field.name)
      }
    }
  }

  private def writeStartSection(buf: Buffer): Unit = {
    writeFuncIdx(buf, module.startFunction.get)
  }

  private def writeCodeSection(buf: Buffer): Unit = {
    buf.vec(module.definedFunctions) { func =>
      buf.byteLengthSubSection(writeFunc(_, func))
    }
  }

  private def writeFunc(buf: Buffer, func: WasmFunction): Unit = {
    buf.vec(func.locals.filter(!_.isParameter)) { local =>
      buf.u32(1)
      writeType(buf, local.typ)
    }

    withLocalIdxValues(func.locals.map(_.name).zipWithIndex.toMap) {
      writeExpr(buf, func.body)
    }
  }

  private def writeType(buf: Buffer, typ: WasmStorageType): Unit = {
    buf.byte(typ.code)
    typ match {
      case WasmRefNullType(heapType) => writeHeapType(buf, heapType)
      case WasmRefType(heapType)     => writeHeapType(buf, heapType)
      case _                         => ()
    }
  }

  private def writeHeapType(buf: Buffer, heapType: WasmHeapType): Unit = {
    heapType match {
      case Type(typeName)   => writeTypeIdxs33(buf, typeName)
      case Func(typeName)   => writeTypeIdxs33(buf, typeName)
      case heapType: Simple => buf.byte(heapType.code)
    }
  }

  private def writeResultType(buf: Buffer, resultType: List[WasmType]): Unit =
    buf.vec(resultType)(writeType(buf, _))

  private def writeTypeIdx(buf: Buffer, typeName: WasmTypeName): Unit =
    buf.u32(typeIdxValues(typeName))

  private def writeTypeIdxs33(buf: Buffer, typeName: WasmTypeName): Unit =
    buf.s33OfUInt(typeIdxValues(typeName))

  private def writeFuncIdx(buf: Buffer, funcName: WasmFunctionName): Unit =
    buf.u32(funcIdxValues(funcName))

  private def writeGlobalIdx(buf: Buffer, globalName: WasmGlobalName): Unit =
    buf.u32(globalIdxValues(globalName))

  private def writeLocalIdx(buf: Buffer, localName: WasmLocalName): Unit = {
    localIdxValues match {
      case Some(values) => buf.u32(values(localName))
      case None         => throw new IllegalStateException(s"Local name table is not available")
    }
  }

  private def writeLabelIdx(buf: Buffer, labelIdx: WasmImmediate.LabelIdx): Unit = {
    val relativeNumber = labelsInScope.indexOf(Some(labelIdx))
    if (relativeNumber < 0)
      throw new IllegalStateException(s"Cannot find $labelIdx in scope")
    buf.u32(relativeNumber)
  }

  private def writeExpr(buf: Buffer, expr: WasmExpr): Unit = {
    for (instr <- expr.instr)
      writeInstr(buf, instr)
    buf.byte(0x0B) // end
  }

  private def writeInstr(buf: Buffer, instr: WasmInstr): Unit = {
    val opcode = instr.opcode
    if (opcode <= 0xff) {
      buf.byte(opcode.toByte)
    } else {
      assert(opcode <= 0xffff, s"cannot encode an opcode longer than 2 bytes yet: ${opcode.toHexString}")
      buf.byte((opcode >>> 8).toByte)
      buf.byte(opcode.toByte)
    }

    for (immediate <- instr.immediates)
      writeImmediate(buf, immediate)

    instr match {
      case instr: WasmInstr.StructuredLabeledInstr =>
        // We must register even the `None` labels, because they contribute to relative numbering
        labelsInScope ::= instr.label
      case END =>
        labelsInScope = labelsInScope.tail
      case _ =>
        ()
    }
  }

  private def writeImmediate(buf: Buffer, immediate: WasmImmediate): Unit = {
    import WasmImmediate._

    immediate match {
      case I32(value) => buf.i32(value)
      case I64(value) => buf.i64(value)
      case F32(value) => buf.f32(value)
      case F64(value) => buf.f64(value)

      case MemArg(offset, align) =>
        buf.u32(offset.toInt)
        buf.u32(align.toInt)

      case BlockType.ValueType(None)        => buf.byte(0x40)
      case BlockType.ValueType(Some(typ))   => writeType(buf, typ)
      case BlockType.FunctionType(typeName) => writeTypeIdxs33(buf, typeName)

      case FuncIdx(value)        => writeFuncIdx(buf, value)
      case labelIdx: LabelIdx    => writeLabelIdx(buf, labelIdx)
      case LabelIdxVector(value) => ???
      case TypeIdx(value)        => writeTypeIdx(buf, value)
      case TableIdx(value)       => ???
      case TagIdx(value)         => ???
      case LocalIdx(value)       => writeLocalIdx(buf, value)
      case GlobalIdx(value)      => writeGlobalIdx(buf, value)
      case HeapType(value)       => writeHeapType(buf, value)
      case StructFieldIdx(value) => buf.u32(value)

      case CastFlags(nullable1, nullable2) =>
        buf.byte(((if (nullable1) 1 else 0) | (if (nullable2) 2 else 0)).toByte)
    }
  }
}

object WasmBinaryWriter {
  private final val SectionType = 0x01
  private final val SectionImport = 0x02
  private final val SectionFunction = 0x03
  private final val SectionTable = 0x04
  private final val SectionMemory = 0x05
  private final val SectionGlobal = 0x06
  private final val SectionExport = 0x07
  private final val SectionStart = 0x08
  private final val SectionElement = 0x09
  private final val SectionCode = 0x0A
  private final val SectionData = 0x0B
  private final val SectionDataCount = 0x0C

  private final class Buffer {
    private val buf = new java.io.ByteArrayOutputStream()

    def result(): Array[Byte] = buf.toByteArray()

    def byte(b: Byte): Unit =
      buf.write(b & 0xff)

    def rawByteArray(array: Array[Byte]): Unit =
      buf.write(array)

    def boolean(b: Boolean): Unit =
      byte(if (b) 1 else 0)

    def u32(value: Int): Unit = unsignedLEB128(Integer.toUnsignedLong(value))

    def s32(value: Int): Unit = signedLEB128(value.toLong)

    def i32(value: Int): Unit = s32(value)

    def s33OfUInt(value: Int): Unit = signedLEB128(Integer.toUnsignedLong(value))

    def u64(value: Long): Unit = unsignedLEB128(value)

    def s64(value: Long): Unit = signedLEB128(value)

    def i64(value: Long): Unit = s64(value)

    def f32(value: Float): Unit = {
      val bits = java.lang.Float.floatToIntBits(value)
      byte(bits.toByte)
      byte((bits >>> 8).toByte)
      byte((bits >>> 16).toByte)
      byte((bits >>> 24).toByte)
    }

    def f64(value: Double): Unit = {
      val bits = java.lang.Double.doubleToLongBits(value)
      byte(bits.toByte)
      byte((bits >>> 8).toByte)
      byte((bits >>> 16).toByte)
      byte((bits >>> 24).toByte)
      byte((bits >>> 32).toByte)
      byte((bits >>> 40).toByte)
      byte((bits >>> 48).toByte)
      byte((bits >>> 56).toByte)
    }

    def vec[A](elems: List[A])(op: A => Unit): Unit = {
      u32(elems.size)
      for (elem <- elems)
        op(elem)
    }

    def opt[A](elemOpt: Option[A])(op: A => Unit): Unit =
      vec(elemOpt.toList)(op)

    def name(s: String): Unit = {
      val utf8 = org.scalajs.ir.UTF8String(s)
      val len = utf8.length
      u32(len)
      var i = 0
      while (i != len) {
        byte(utf8(i))
        i += 1
      }
    }

    def byteLengthSubSection(f: Buffer => Unit): Unit = {
      val subBuffer = new Buffer()
      f(subBuffer)
      val subResult = subBuffer.result()

      this.u32(subResult.length)
      this.rawByteArray(subResult)
    }

    @tailrec
    private def unsignedLEB128(value: Long): Unit = {
      val next = value >>> 7
      if (next == 0) {
        buf.write(value.toInt)
      } else {
        buf.write((value.toInt & 0x7f) | 0x80)
        unsignedLEB128(next)
      }
    }

    @tailrec
    private def signedLEB128(value: Long): Unit = {
      val chunk = value.toInt & 0x7f
      val next = value >> 7
      if (next == (if ((chunk & 0x40) != 0) -1 else 0)) {
        buf.write(chunk)
      } else {
        buf.write(chunk | 0x80)
        signedLEB128(next)
      }
    }
  }
}
