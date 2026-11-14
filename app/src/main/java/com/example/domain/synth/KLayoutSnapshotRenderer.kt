package com.example.domain.synth

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class ParsedGdsPolygon(
  val layer: Int,
  val datatype: Int,
  val minXUm: Double,
  val minYUm: Double,
  val maxXUm: Double,
  val maxYUm: Double,
)

data class ParsedGdsText(
  val layer: Int,
  val texttype: Int,
  val xUm: Double,
  val yUm: Double,
  val text: String,
)

data class ParsedGdsSref(
  val targetName: String,
  val xUm: Double,
  val yUm: Double,
  val mirrorX: Boolean,
)

data class ParsedGdsStructure(
  val name: String,
  val polygons: List<ParsedGdsPolygon>,
  val texts: List<ParsedGdsText>,
  val srefs: List<ParsedGdsSref>,
)

data class ParsedGdsStream(
  val isValid: Boolean,
  val headerVersion: Int,
  val libraryName: String,
  val userUnitPerDbu: Double,
  val metersPerDbu: Double,
  val topStructureName: String,
  val structureCount: Int,
  val srefCount: Int,
  val flattenedPolygons: List<ParsedGdsPolygon>,
  val flattenedTexts: List<ParsedGdsText>,
  val layerCounts: Map<Pair<Int, Int>, Int>,
  val errorMessage: String? = null,
)

/**
 * Pure-Kotlin Calma GDSII v6.0.0 binary reader/validator and KLayout-style PNG snapshot renderer.
 * Reads back the serialized `.gds` byte stream, resolves BGNSTR + SREF hierarchy (including MX
 * reflection transforms), validates record framing, and renders a KLayout-palette layout snapshot
 * with stippled metal/well layers, micron axes, and companion `.lyp` Layer Properties XML.
 */
object KLayoutSnapshotRenderer {

  fun parseAndVerifyGds(gdsBytes: ByteArray): ParsedGdsStream {
    if (gdsBytes.size < 12) {
      return ParsedGdsStream(
        isValid = false,
        headerVersion = 0,
        libraryName = "",
        userUnitPerDbu = 1e-3,
        metersPerDbu = 1e-9,
        topStructureName = "",
        structureCount = 0,
        srefCount = 0,
        flattenedPolygons = emptyList(),
        flattenedTexts = emptyList(),
        layerCounts = emptyMap(),
        errorMessage = "GDS stream too small (${gdsBytes.size} bytes).",
      )
    }

    val buf = ByteBuffer.wrap(gdsBytes).order(ByteOrder.BIG_ENDIAN)
    var headerVersion = 0
    var libName = ""
    var userUnit = 1e-3
    var metersUnit = 1e-9
    var sawEndLib = false

    val structures = linkedMapOf<String, ParsedGdsStructure>()
    var curStrName = ""
    var curPolys = mutableListOf<ParsedGdsPolygon>()
    var curTexts = mutableListOf<ParsedGdsText>()
    var curSrefs = mutableListOf<ParsedGdsSref>()

    // Active element state
    var elemType = 0 // 1=BOUNDARY, 2=SREF, 3=TEXT
    var curLayer = 0
    var curDatatype = 0
    var curXy = IntArray(0)
    var curSname = ""
    var curStrans = 0
    var curTextStr = ""

    while (buf.remaining() >= 4) {
      val len = buf.short.toInt() and 0xFFFF
      val rec = buf.short
      if (len < 4 || len - 4 > buf.remaining()) {
        return ParsedGdsStream(
          isValid = false,
          headerVersion = headerVersion,
          libraryName = libName,
          userUnitPerDbu = userUnit,
          metersPerDbu = metersUnit,
          topStructureName = curStrName,
          structureCount = structures.size,
          srefCount = 0,
          flattenedPolygons = emptyList(),
          flattenedTexts = emptyList(),
          layerCounts = emptyMap(),
          errorMessage = "Truncated or malformed GDS record (len=$len).",
        )
      }
      val payload = ByteArray(len - 4)
      buf.get(payload)
      val pBuf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

      when (rec) {
        GdsiiBinaryWriter.REC_HEADER -> if (payload.size >= 2) headerVersion = pBuf.short.toInt() and 0xFFFF
        GdsiiBinaryWriter.REC_LIBNAME -> libName = decodeAscii(payload)
        GdsiiBinaryWriter.REC_UNITS -> if (payload.size >= 16) {
          userUnit = GdsiiBinaryWriter.decodeGdsReal8(payload, 0)
          metersUnit = GdsiiBinaryWriter.decodeGdsReal8(payload, 8)
        }
        GdsiiBinaryWriter.REC_BGNSTR -> {
          curStrName = ""
          curPolys = mutableListOf()
          curTexts = mutableListOf()
          curSrefs = mutableListOf()
        }
        GdsiiBinaryWriter.REC_STRNAME -> curStrName = decodeAscii(payload)
        GdsiiBinaryWriter.REC_ENDSTR -> {
          val name = curStrName.ifBlank { "CELL_${structures.size}" }
          structures[name] = ParsedGdsStructure(name, curPolys, curTexts, curSrefs)
        }
        GdsiiBinaryWriter.REC_BOUNDARY -> {
          elemType = 1
          curLayer = 0
          curDatatype = 0
          curXy = IntArray(0)
        }
        GdsiiBinaryWriter.REC_SREF -> {
          elemType = 2
          curSname = ""
          curStrans = 0
          curXy = IntArray(0)
        }
        GdsiiBinaryWriter.REC_TEXT -> {
          elemType = 3
          curLayer = 0
          curDatatype = 0
          curXy = IntArray(0)
          curTextStr = ""
        }
        GdsiiBinaryWriter.REC_LAYER -> if (payload.size >= 2) curLayer = pBuf.short.toInt() and 0xFFFF
        GdsiiBinaryWriter.REC_DATATYPE, GdsiiBinaryWriter.REC_TEXTTYPE ->
          if (payload.size >= 2) curDatatype = pBuf.short.toInt() and 0xFFFF
        GdsiiBinaryWriter.REC_SNAME -> curSname = decodeAscii(payload)
        GdsiiBinaryWriter.REC_STRANS -> if (payload.size >= 2) curStrans = pBuf.short.toInt() and 0xFFFF
        GdsiiBinaryWriter.REC_STRING -> curTextStr = decodeAscii(payload)
        GdsiiBinaryWriter.REC_XY -> {
          val count = payload.size / 4
          curXy = IntArray(count) { pBuf.int }
        }
        GdsiiBinaryWriter.REC_ENDEL -> {
          val scale = if (userUnit > 0.0) userUnit else 1e-3
          when (elemType) {
            1 -> if (curXy.size >= 8) {
              var minX = Double.POSITIVE_INFINITY
              var minY = Double.POSITIVE_INFINITY
              var maxX = Double.NEGATIVE_INFINITY
              var maxY = Double.NEGATIVE_INFINITY
              var i = 0
              while (i + 1 < curXy.size) {
                val x = curXy[i] * scale
                val y = curXy[i + 1] * scale
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                i += 2
              }
              curPolys += ParsedGdsPolygon(curLayer, curDatatype, minX, minY, maxX, maxY)
            }
            2 -> if (curXy.size >= 2 && curSname.isNotBlank()) {
              val mirrorX = (curStrans and 0x8000) != 0
              curSrefs += ParsedGdsSref(curSname, curXy[0] * scale, curXy[1] * scale, mirrorX)
            }
            3 -> if (curXy.size >= 2 && curTextStr.isNotBlank()) {
              curTexts += ParsedGdsText(curLayer, curDatatype, curXy[0] * scale, curXy[1] * scale, curTextStr)
            }
          }
          elemType = 0
        }
        GdsiiBinaryWriter.REC_ENDLIB -> sawEndLib = true
      }
    }

    if (!sawEndLib || structures.isEmpty()) {
      return ParsedGdsStream(
        isValid = false,
        headerVersion = headerVersion,
        libraryName = libName,
        userUnitPerDbu = userUnit,
        metersPerDbu = metersUnit,
        topStructureName = "",
        structureCount = structures.size,
        srefCount = 0,
        flattenedPolygons = emptyList(),
        flattenedTexts = emptyList(),
        layerCounts = emptyMap(),
        errorMessage = if (!sawEndLib) "Missing ENDLIB terminator." else "No BGNSTR cells found in GDS stream.",
      )
    }

    val top = structures.values.last()
    val flatPolys = ArrayList<ParsedGdsPolygon>(top.polygons.size + top.srefs.size * 12)
    val flatTexts = ArrayList<ParsedGdsText>(top.texts.size)
    flatPolys += top.polygons
    flatTexts += top.texts

    top.srefs.forEach { ref ->
      val child = structures[ref.targetName] ?: return@forEach
      child.polygons.forEach { p ->
        val (y0, y1) = if (ref.mirrorX) {
          (ref.yUm - p.maxYUm) to (ref.yUm - p.minYUm)
        } else {
          (ref.yUm + p.minYUm) to (ref.yUm + p.maxYUm)
        }
        flatPolys += ParsedGdsPolygon(
          layer = p.layer,
          datatype = p.datatype,
          minXUm = ref.xUm + p.minXUm,
          minYUm = min(y0, y1),
          maxXUm = ref.xUm + p.maxXUm,
          maxYUm = max(y0, y1),
        )
      }
    }

    val counts = linkedMapOf<Pair<Int, Int>, Int>()
    flatPolys.forEach { p ->
      val key = p.layer to p.datatype
      counts[key] = (counts[key] ?: 0) + 1
    }

    return ParsedGdsStream(
      isValid = true,
      headerVersion = headerVersion,
      libraryName = libName,
      userUnitPerDbu = userUnit,
      metersPerDbu = metersUnit,
      topStructureName = top.name,
      structureCount = structures.size,
      srefCount = top.srefs.size,
      flattenedPolygons = flatPolys,
      flattenedTexts = flatTexts,
      layerCounts = counts,
    )
  }

  /**
   * Renders a crisp KLayout-style dark-viewport PNG Bitmap directly from a parsed Calma GDSII stream.
   */
  fun renderSnapshotBitmap(
    parsed: ParsedGdsStream,
    sha256Short: String,
    widthPx: Int = 1280,
    heightPx: Int = 860,
  ): Bitmap {
    val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Authentic KLayout charcoal background
    canvas.drawColor(Color.parseColor("#11151E"))

    val headerH = 54f
    val footerH = 44f
    val leftAxisPad = 76f
    val rightPad = 28f
    val topAxisPad = headerH + 22f
    val bottomAxisPad = footerH + 34f

    val plotW = (widthPx - leftAxisPad - rightPad).coerceAtLeast(100f)
    val plotH = (heightPx - topAxisPad - bottomAxisPad).coerceAtLeast(100f)

    // Top bar (KLayout window bar)
    val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#182030") }
    canvas.drawRect(0f, 0f, widthPx.toFloat(), headerH, barPaint)
    canvas.drawRect(0f, heightPx - footerH, widthPx.toFloat(), heightPx.toFloat(), barPaint)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#F1F5F9")
      textSize = 20f
      typeface = Typeface.MONOSPACE
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#94A3B8")
      textSize = 15f
      typeface = Typeface.MONOSPACE
    }
    canvas.drawText(
      "KLayout GDS Snapshot · ${parsed.topStructureName}.gds (v${parsed.headerVersion})",
      20f,
      24f,
      titlePaint,
    )
    canvas.drawText(
      "${parsed.structureCount} cells · ${parsed.srefCount} SREF · ${parsed.flattenedPolygons.size} polygons · ${parsed.flattenedTexts.size} pins · sha256:$sha256Short",
      20f,
      44f,
      subPaint,
    )

    // Bounding box across all flattened polygons
    val minX = parsed.flattenedPolygons.minOfOrNull { it.minXUm } ?: 0.0
    val minY = parsed.flattenedPolygons.minOfOrNull { it.minYUm } ?: 0.0
    val maxX = parsed.flattenedPolygons.maxOfOrNull { it.maxXUm } ?: 40.0
    val maxY = parsed.flattenedPolygons.maxOfOrNull { it.maxYUm } ?: 40.0
    val worldW = (maxX - minX).coerceAtLeast(1.0)
    val worldH = (maxY - minY).coerceAtLeast(1.0)

    val scale = min(plotW / worldW.toFloat(), plotH / worldH.toFloat())
    val drawnW = worldW.toFloat() * scale
    val drawnH = worldH.toFloat() * scale
    val originX = leftAxisPad + (plotW - drawnW) / 2f
    val originYBottom = (heightPx - bottomAxisPad) - (plotH - drawnH) / 2f

    // KLayout uses Cartesian +Y up!
    fun mapX(xUm: Double): Float = originX + ((xUm - minX).toFloat() * scale)
    fun mapY(yUm: Double): Float = originYBottom - ((yUm - minY).toFloat() * scale)

    // Subtle KLayout grid & micron ticks
    val gridPaint = Paint().apply {
      color = Color.parseColor("#1F2937")
      strokeWidth = 1f
    }
    val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#64748B")
      textSize = 13f
      typeface = Typeface.MONOSPACE
    }
    val ticks = 5
    for (i in 0..ticks) {
      val frac = i.toFloat() / ticks
      val gx = originX + drawnW * frac
      val gy = originYBottom - drawnH * frac
      canvas.drawLine(gx, originYBottom - drawnH, gx, originYBottom, gridPaint)
      canvas.drawLine(originX, gy, originX + drawnW, gy, gridPaint)
      val xVal = minX + worldW * frac
      val yVal = minY + worldH * frac
      canvas.drawText("${"%.1f".format(Locale.US, xVal)}µm", gx - 16f, originYBottom + 18f, axisLabelPaint)
      canvas.drawText("${"%.1f".format(Locale.US, yVal)}", 12f, gy + 4f, axisLabelPaint)
    }

    // Build KLayout-style stipple paints cleanly by layer order (bottom-up stack)
    val nwellFill = createHatchPaint(Color.parseColor("#22C55E"), alpha = 68, step = 10, diagB = false)
    val diffFill = createSolidPaint(Color.parseColor("#65A30D"), alpha = 105)
    val polyFill = createSolidPaint(Color.parseColor("#EF4444"), alpha = 175)
    val li1Fill = createHatchPaint(Color.parseColor("#C084FC"), alpha = 135, step = 8, diagB = true)
    val cellOutline = createStrokePaint(Color.parseColor("#475569"), 1.2f)
    val met1Fill = createHatchPaint(Color.parseColor("#38BDF8"), alpha = 165, step = 8, diagB = false)
    val met2Fill = createHatchPaint(Color.parseColor("#A78BFA"), alpha = 165, step = 8, diagB = true)
    val met3Fill = createHatchPaint(Color.parseColor("#10B981"), alpha = 190, step = 7, diagB = false)
    val clkFill = createSolidPaint(Color.parseColor("#F59E0B"), alpha = 225)
    val viaFill = createSolidPaint(Color.parseColor("#FACC15"), alpha = 245)
    val viaStroke = createStrokePaint(Color.parseColor("#FEF08A"), 1.2f)
    val pinFill = createSolidPaint(Color.parseColor("#22D3EE"), alpha = 200)
    val dieBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#E2E8F0")
      style = Paint.Style.STROKE
      strokeWidth = 2f
      pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    val layerDrawOrder = listOf(
      64 to 20, // NWELL
      65 to 20, // DIFF
      66 to 20, // POLY
      67 to 20, // LI1
      67 to 44, // MCON
      81 to 4,  // areaid.standardc
      68 to 20, // MET1
      68 to 44, // VIA1
      69 to 20, // MET2
      69 to 44, // VIA2
      70 to 20, // MET3
      70 to 44, // VIA3
      71 to 20, // MET4 CLK
      72 to 20, // IO PINS
      235 to 4, // DIE prBoundary
    )

    val byLayer = parsed.flattenedPolygons.groupBy { it.layer to it.datatype }
    val rect = RectF()
    layerDrawOrder.forEach { key ->
      val list = byLayer[key].orEmpty()
      if (list.isEmpty()) return@forEach
      val (layer, dt) = key
      list.forEach { p ->
        val l = mapX(p.minXUm)
        val r = mapX(p.maxXUm)
        val t = mapY(p.maxYUm)
        val b = mapY(p.minYUm)
        rect.set(l, t, max(r, l + 1.5f), max(b, t + 1.5f))

        when {
          layer == 235 -> canvas.drawRect(rect, dieBorder)
          layer == 81 -> canvas.drawRect(rect, cellOutline)
          layer == 64 -> canvas.drawRect(rect, nwellFill)
          layer == 65 -> canvas.drawRect(rect, diffFill)
          layer == 66 -> canvas.drawRect(rect, polyFill)
          layer == 67 && dt == 20 -> canvas.drawRect(rect, li1Fill)
          layer == 67 && dt == 44 -> canvas.drawRect(rect, viaFill)
          layer == 68 && dt == 20 -> canvas.drawRect(rect, met1Fill)
          layer == 69 && dt == 20 -> canvas.drawRect(rect, met2Fill)
          layer == 70 && dt == 20 -> canvas.drawRect(rect, met3Fill)
          layer == 71 -> canvas.drawRect(rect, clkFill)
          layer == 72 -> canvas.drawRect(rect, pinFill)
          dt == 44 -> {
            canvas.drawRect(rect, viaFill)
            canvas.drawRect(rect, viaStroke)
          }
          else -> canvas.drawRect(rect, met1Fill)
        }
      }
    }

    // Draw pin text labels
    val pinTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#F8FAFC")
      textSize = 14f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    parsed.flattenedTexts.forEach { t ->
      canvas.drawText(t.text, mapX(t.xUm) + 6f, mapY(t.yUm) + 5f, pinTextPaint)
    }

    // Bottom Layer Swatch Bar
    val legendItems = listOf(
      "NWELL 64/20" to "#22C55E",
      "DIFF 65/20" to "#65A30D",
      "POLY 66/20" to "#EF4444",
      "LI1 67/20" to "#C084FC",
      "MET1 68/20" to "#38BDF8",
      "MET2 69/20" to "#A78BFA",
      "MET3 70/20" to "#10B981",
      "CLK 71/20" to "#F59E0B",
      "VIA 68/44" to "#FACC15",
    )
    val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val legendText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#CBD5E1")
      textSize = 13f
      typeface = Typeface.MONOSPACE
    }
    var lx = 20f
    val ly = heightPx - footerH / 2f + 5f
    legendItems.forEach { (label, hex) ->
      swatchPaint.color = Color.parseColor(hex)
      canvas.drawRect(lx, ly - 11f, lx + 12f, ly + 1f, swatchPaint)
      canvas.drawText(label, lx + 16f, ly, legendText)
      lx += 136f
    }

    return bmp
  }

  fun encodePng(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream(65536)
    bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
    return out.toByteArray()
  }

  /**
   * Emits a real KLayout Layer Properties (.lyp) XML matching our GDSII stream layers so users can
   * open `<design>.gds` and `<design>.lyp` together in desktop/Termux KLayout with exact colors.
   */
  fun generateKLayoutLypXml(moduleName: String): String = buildString {
    appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
    appendLine("<layer-properties>")
    val defs = listOf(
      Triple("235/4", "prBoundary", "#e2e8f0" to "I2"),
      Triple("64/20", "nwell.drawing", "#22c55e" to "I9"),
      Triple("65/20", "diff.drawing", "#65a30d" to "I5"),
      Triple("66/20", "poly.drawing", "#ef4444" to "I1"),
      Triple("67/20", "li1.drawing", "#c084fc" to "I9"),
      Triple("67/44", "mcon.drawing", "#facc15" to "I0"),
      Triple("81/4", "areaid.standardc ($moduleName)", "#64748b" to "I1"),
      Triple("68/20", "met1.drawing", "#38bdf8" to "I5"),
      Triple("68/44", "via.drawing", "#facc15" to "I0"),
      Triple("69/20", "met2.drawing", "#a78bfa" to "I9"),
      Triple("69/44", "via2.drawing", "#fde047" to "I0"),
      Triple("70/20", "met3.drawing", "#10b981" to "I5"),
      Triple("70/44", "via3.drawing", "#fbbf24" to "I0"),
      Triple("71/20", "met4.drawing (CLK)", "#f59e0b" to "I0"),
      Triple("72/20", "met5.pin", "#22d3ee" to "I0"),
    )
    defs.forEach { (source, name, style) ->
      val (colorHex, dither) = style
      appendLine("  <properties>")
      appendLine("    <frame-color>$colorHex</frame-color>")
      appendLine("    <fill-color>$colorHex</fill-color>")
      appendLine("    <frame-brightness>0</frame-brightness>")
      appendLine("    <fill-brightness>0</fill-brightness>")
      appendLine("    <dither-pattern>$dither</dither-pattern>")
      appendLine("    <valid>true</valid>")
      appendLine("    <visible>true</visible>")
      appendLine("    <name>$name</name>")
      appendLine("    <source>$source</source>")
      appendLine("  </properties>")
    }
    appendLine("</layer-properties>")
  }

  private fun decodeAscii(bytes: ByteArray): String {
    var end = bytes.size
    while (end > 0 && bytes[end - 1] == 0.toByte()) end--
    return String(bytes, 0, end, Charsets.US_ASCII)
  }

  private fun createSolidPaint(colorInt: Int, alpha: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(alpha, Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
    style = Paint.Style.FILL
  }

  private fun createStrokePaint(colorInt: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = colorInt
    style = Paint.Style.STROKE
    strokeWidth = width
  }

  private fun createHatchPaint(colorInt: Int, alpha: Int, step: Int, diagB: Boolean): Paint {
    val tile = Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888)
    val c = Canvas(tile)
    val lineColor = Color.argb(alpha, Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
    val fillBg = Color.argb((alpha * 0.28f).toInt(), Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
    c.drawColor(fillBg)
    val p = Paint().apply {
      color = lineColor
      strokeWidth = 1.6f
    }
    if (diagB) {
      c.drawLine(0f, 0f, step.toFloat(), step.toFloat(), p)
    } else {
      c.drawLine(0f, step.toFloat(), step.toFloat(), 0f, p)
    }
    return Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
      style = Paint.Style.FILL
    }
  }
}
