package com.example.domain.synth

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Physical mask layer numbers aligned with SKY130A / OpenPDK GDSII conventions for KLayout & Magic.
 */
enum class PhysicalMaskLayer(
  val layerNumber: Int,
  val datatype: Int,
  val shortName: String,
  val displayName: String,
) {
  DIE_AREA(235, 4, "DIE", "prBoundary"),
  NWELL(64, 20, "NWELL", "nwell.drawing"),
  DIFF(65, 20, "DIFF", "diff.drawing"),
  POLY(66, 20, "POLY", "poly.drawing"),
  LI1(67, 20, "LI1", "li1.drawing"),
  MCON(67, 44, "MCON", "mcon.drawing"),
  CELLS(81, 4, "CELLS", "areaid.standardc"),
  MET1(68, 20, "MET1", "met1.drawing"),
  VIA1(68, 44, "VIA1", "via.drawing"),
  MET2(69, 20, "MET2", "met2.drawing"),
  VIA2(69, 44, "VIA2", "via2.drawing"),
  MET3(70, 20, "MET3", "met3.drawing"),
  VIA3(70, 44, "VIA3", "via3.drawing"),
  CLK_TREE(71, 20, "CLK", "met4.drawing (CLK)"),
  IO_PINS(72, 20, "PINS", "met5.pin"),
}

data class GdsRectPolygon(
  val layer: PhysicalMaskLayer,
  val xUm: Double,
  val yUm: Double,
  val widthUm: Double,
  val heightUm: Double,
  val label: String = "",
  val netName: String = "",
)

data class GdsCellMacro(
  val structureName: String,
  val widthUm: Double,
  val heightUm: Double,
  val shapes: List<GdsRectPolygon>,
)

data class GdsCellInstance(
  val structureName: String,
  val xUm: Double,
  val yUm: Double,
  val mirrorX: Boolean = false,
)

data class GdsBinaryArtifact(
  val topModule: String,
  val bytes: ByteArray,
  val sha256Hex: String,
  val polygonCount: Int,
  val structureCount: Int = 1,
  val srefCount: Int = 0,
)

/**
 * Pure-Kotlin Calma GDSII v6.0.0 binary stream writer.
 * Emits big-endian GDSII records (1 nm database unit, 0.001 um user unit) with hierarchical
 * standard-cell BGNSTR macros, SREF instances (including CMOS row MX reflection), BOUNDARY
 * closed polygons, and TEXT pin labels compatible with KLayout, Magic, and gdstk.
 */
object GdsiiBinaryWriter {

  internal const val REC_HEADER: Short = 0x0002
  internal const val REC_BGNLIB: Short = 0x0102
  internal const val REC_LIBNAME: Short = 0x0206
  internal const val REC_UNITS: Short = 0x0305
  internal const val REC_ENDLIB: Short = 0x0400
  internal const val REC_BGNSTR: Short = 0x0502
  internal const val REC_STRNAME: Short = 0x0606
  internal const val REC_ENDSTR: Short = 0x0700
  internal const val REC_BOUNDARY: Short = 0x0800
  internal const val REC_SREF: Short = 0x0A00
  internal const val REC_TEXT: Short = 0x0C00
  internal const val REC_LAYER: Short = 0x0D02
  internal const val REC_DATATYPE: Short = 0x0E02
  internal const val REC_XY: Short = 0x1003
  internal const val REC_ENDEL: Short = 0x1100
  internal const val REC_SNAME: Short = 0x1206
  internal const val REC_TEXTTYPE: Short = 0x1602
  internal const val REC_PRESENTATION: Short = 0x1701
  internal const val REC_STRING: Short = 0x1906
  internal const val REC_STRANS: Short = 0x1A01
  internal const val REC_MAG: Short = 0x1B05

  fun writeStream(
    topModuleName: String,
    polygons: List<GdsRectPolygon>,
    pinLabels: List<GdsRectPolygon> = emptyList(),
    cellMacros: List<GdsCellMacro> = emptyList(),
    cellInstances: List<GdsCellInstance> = emptyList(),
  ): GdsBinaryArtifact {
    val cleanModule = sanitizeGdsName(topModuleName, "rtl_top")

    val baos = ByteArrayOutputStream(8192 + (polygons.size + cellMacros.sumOf { it.shapes.size }) * 56)
    val out = DataOutputStream(baos)
    val ts = currentGdsTimestamp()

    // 1. HEADER v6.0.0
    writeShortRecord(out, REC_HEADER, shortArrayOf(600))
    // 2. BGNLIB
    writeShortRecord(out, REC_BGNLIB, ts)
    // 3. LIBNAME
    writeStringRecord(out, REC_LIBNAME, sanitizeGdsName("${cleanModule}_lib", "rtl_lib"))
    // 4. UNITS: 0.001 um/DBU (1 nm), 1e-9 m/DBU
    writeReal8PairRecord(out, REC_UNITS, 1e-3, 1e-9)

    var totalShapesWritten = 0

    // 5. Leaf Standard-Cell Macro Structures (written before top cell for clean hierarchical linking)
    val definedMacros = linkedSetOf<String>()
    cellMacros.forEach { macro ->
      val macroName = sanitizeGdsName(macro.structureName, "STD_CELL")
      if (!definedMacros.add(macroName) || macroName == cleanModule) return@forEach
      writeShortRecord(out, REC_BGNSTR, ts)
      writeStringRecord(out, REC_STRNAME, macroName)
      macro.shapes.forEach { poly ->
        if (writeBoundaryRect(out, poly)) totalShapesWritten++
      }
      writeNoDataRecord(out, REC_ENDSTR)
    }

    // 6. Top-Level Structure
    writeShortRecord(out, REC_BGNSTR, ts)
    writeStringRecord(out, REC_STRNAME, cleanModule)

    // 6a. Hierarchical SREF Instances
    var writtenSrefs = 0
    cellInstances.forEach { inst ->
      val targetName = sanitizeGdsName(inst.structureName, "STD_CELL")
      if (targetName !in definedMacros) return@forEach
      writeNoDataRecord(out, REC_SREF)
      writeStringRecord(out, REC_SNAME, targetName)
      if (inst.mirrorX) {
        // Bit 15 (0x8000): Reflection across X-axis (standard CMOS alternating row flip)
        writeShortRecord(out, REC_STRANS, shortArrayOf(0x8000.toShort()))
      }
      writeIntRecord(out, REC_XY, intArrayOf(umToNm(inst.xUm), umToNm(inst.yUm)))
      writeNoDataRecord(out, REC_ENDEL)
      writtenSrefs++
    }

    // 6b. Top-Level Routing, Power Rings, Clock Tree, Die Boundary & IO Pins
    polygons.forEach { poly ->
      if (writeBoundaryRect(out, poly)) totalShapesWritten++
    }

    // 6c. Pin TEXT Labels
    pinLabels.forEach { pin ->
      if (pin.label.isBlank()) return@forEach
      val cx = umToNm(pin.xUm + pin.widthUm / 2.0)
      val cy = umToNm(pin.yUm + pin.heightUm / 2.0)
      writeNoDataRecord(out, REC_TEXT)
      writeShortRecord(out, REC_LAYER, shortArrayOf(pin.layer.layerNumber.toShort()))
      writeShortRecord(out, REC_TEXTTYPE, shortArrayOf(pin.layer.datatype.toShort()))
      writeShortRecord(out, REC_PRESENTATION, shortArrayOf(0x0005.toShort())) // middle-center
      writeShortRecord(out, REC_STRANS, shortArrayOf(0x0000.toShort()))
      writeReal8Record(out, REC_MAG, 0.35)
      writeIntRecord(out, REC_XY, intArrayOf(cx, cy))
      writeStringRecord(out, REC_STRING, pin.label.take(44))
      writeNoDataRecord(out, REC_ENDEL)
      totalShapesWritten++
    }

    writeNoDataRecord(out, REC_ENDSTR)
    writeNoDataRecord(out, REC_ENDLIB)
    out.flush()

    val bytes = baos.toByteArray()
    val sha256 = MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }

    return GdsBinaryArtifact(
      topModule = cleanModule,
      bytes = bytes,
      sha256Hex = sha256,
      polygonCount = totalShapesWritten,
      structureCount = definedMacros.size + 1,
      srefCount = writtenSrefs,
    )
  }

  private fun writeBoundaryRect(out: DataOutputStream, rect: GdsRectPolygon): Boolean {
    if (rect.widthUm <= 0.0 || rect.heightUm <= 0.0) return false
    val x0 = umToNm(rect.xUm)
    val y0 = umToNm(rect.yUm)
    val x1 = umToNm(rect.xUm + rect.widthUm)
    val y1 = umToNm(rect.yUm + rect.heightUm)
    if (x1 <= x0 || y1 <= y0) return false

    writeNoDataRecord(out, REC_BOUNDARY)
    writeShortRecord(out, REC_LAYER, shortArrayOf(rect.layer.layerNumber.toShort()))
    writeShortRecord(out, REC_DATATYPE, shortArrayOf(rect.layer.datatype.toShort()))
    writeIntRecord(
      out,
      REC_XY,
      intArrayOf(
        x0, y0,
        x1, y0,
        x1, y1,
        x0, y1,
        x0, y0,
      ),
    )
    writeNoDataRecord(out, REC_ENDEL)
    return true
  }

  private fun sanitizeGdsName(raw: String, fallback: String): String =
    raw.trim()
      .ifBlank { fallback }
      .replace(Regex("[^A-Za-z0-9_$]"), "_")
      .take(32)

  private fun umToNm(um: Double): Int = (um * 1000.0).roundToInt()

  private fun currentGdsTimestamp(): ShortArray {
    val cal = Calendar.getInstance()
    val yr = cal.get(Calendar.YEAR).toShort()
    val mo = (cal.get(Calendar.MONTH) + 1).toShort()
    val day = cal.get(Calendar.DAY_OF_MONTH).toShort()
    val hr = cal.get(Calendar.HOUR_OF_DAY).toShort()
    val min = cal.get(Calendar.MINUTE).toShort()
    val sec = cal.get(Calendar.SECOND).toShort()
    return shortArrayOf(yr, mo, day, hr, min, sec, yr, mo, day, hr, min, sec)
  }

  private fun writeNoDataRecord(out: DataOutputStream, recordHeader: Short) {
    out.writeShort(4)
    out.writeShort(recordHeader.toInt())
  }

  private fun writeShortRecord(out: DataOutputStream, recordHeader: Short, values: ShortArray) {
    val len = 4 + values.size * 2
    out.writeShort(len)
    out.writeShort(recordHeader.toInt())
    for (v in values) out.writeShort(v.toInt())
  }

  private fun writeIntRecord(out: DataOutputStream, recordHeader: Short, values: IntArray) {
    val len = 4 + values.size * 4
    out.writeShort(len)
    out.writeShort(recordHeader.toInt())
    for (v in values) out.writeInt(v)
  }

  private fun writeStringRecord(out: DataOutputStream, recordHeader: Short, text: String) {
    val raw = text.toByteArray(Charsets.US_ASCII)
    val needsPad = raw.size % 2 != 0
    val totalLen = 4 + raw.size + if (needsPad) 1 else 0
    out.writeShort(totalLen)
    out.writeShort(recordHeader.toInt())
    out.write(raw)
    if (needsPad) out.writeByte(0)
  }

  private fun writeReal8Record(out: DataOutputStream, recordHeader: Short, value: Double) {
    out.writeShort(12)
    out.writeShort(recordHeader.toInt())
    out.write(encodeGdsReal8(value))
  }

  private fun writeReal8PairRecord(
    out: DataOutputStream,
    recordHeader: Short,
    first: Double,
    second: Double,
  ) {
    out.writeShort(20)
    out.writeShort(recordHeader.toInt())
    out.write(encodeGdsReal8(first))
    out.write(encodeGdsReal8(second))
  }

  /**
   * Calma GDSII 8-byte excess-64 base-16 floating point encoder.
   */
  internal fun encodeGdsReal8(value: Double): ByteArray {
    if (value == 0.0) return ByteArray(8)
    val negative = value < 0.0
    var mantissa = if (negative) -value else value
    var exp16 = 0
    while (mantissa >= 1.0) {
      mantissa /= 16.0
      exp16++
    }
    while (mantissa < 1.0 / 16.0) {
      mantissa *= 16.0
      exp16--
    }
    val biasedExp = (exp16 + 64) and 0x7F
    val firstByte = ((if (negative) 0x80 else 0) or biasedExp).toByte()
    val scaled = (mantissa * (1L shl 56).toDouble()).toLong().coerceIn(0L, (1L shl 56) - 1L)
    val out = ByteArray(8)
    out[0] = firstByte
    for (i in 1..7) {
      val shift = (7 - i) * 8
      out[i] = ((scaled ushr shift) and 0xFFL).toByte()
    }
    return out
  }

  /**
   * Decodes Calma GDSII 8-byte excess-64 base-16 floating point values (for stream verification).
   */
  internal fun decodeGdsReal8(bytes: ByteArray, offset: Int = 0): Double {
    if (bytes.size < offset + 8) return 0.0
    val header = bytes[offset].toInt() and 0xFF
    val negative = (header and 0x80) != 0
    val exp16 = (header and 0x7F) - 64
    var frac = 0L
    for (i in 1..7) {
      frac = (frac shl 8) or (bytes[offset + i].toLong() and 0xFFL)
    }
    if (frac == 0L) return 0.0
    val mantissa = frac.toDouble() / (1L shl 56).toDouble()
    val value = mantissa * Math.pow(16.0, exp16.toDouble())
    return if (negative) -value else value
  }
}
