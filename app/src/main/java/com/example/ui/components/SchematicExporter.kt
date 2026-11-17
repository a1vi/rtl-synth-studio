package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.example.domain.synth.RoutedSchematicLayout
import com.example.domain.synth.SchematicCellCategory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil

object SchematicExporter {

  fun buildSvgString(
    layout: RoutedSchematicLayout,
    showNetLabels: Boolean = true,
  ): String {
    val w = ceil(layout.boundsWidth.toDouble()).toInt().coerceAtLeast(720)
    val h = ceil(layout.boundsHeight.toDouble()).toInt().coerceAtLeast(460) + 28
    val sb = StringBuilder()

    sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    sb.appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h">""")
    sb.appendLine("""  <rect x="0" y="0" width="$w" height="$h" fill="#000000"/>""")

    // Title Bar
    val title = "${escapeXml(layout.graph.topModuleName)} :: ${escapeXml(layout.graph.scopeTitle)} (${layout.placedCells.size} cells, ${layout.routedNets.size} nets)"
    sb.appendLine("""  <text x="16" y="20" fill="#55CC77" font-family="monospace" font-size="12" font-weight="bold">$title</text>""")
    sb.appendLine("""  <line x1="0" y1="28" x2="$w" y2="28" stroke="#1E2820" stroke-width="1"/>""")

    sb.appendLine("""  <g transform="translate(0, 28)">""")

    // 1. Orthogonal green wires
    layout.routedNets.forEach { rNet ->
      val wireHex = when {
        rNet.net.isCritical -> "#FF6655"
        else -> "#55CC77"
      }
      val strokeW = if (rNet.net.width > 1 || rNet.net.isCritical) "1.8" else "1.2"
      rNet.branches.forEach { branch ->
        val ptsAttr = branch.points.joinToString(" ") { p ->
          "${formatCoord(p.x)},${formatCoord(p.y)}"
        }
        val dash = if (branch.isBackEdge) """ stroke-dasharray="5,3"""" else ""
        sb.appendLine("""    <polyline fill="none" stroke="$wireHex" stroke-width="$strokeW"$dash points="$ptsAttr"/>""")
      }
      rNet.junctionPoints.forEach { jp ->
        sb.appendLine("""    <circle cx="${formatCoord(jp.x)}" cy="${formatCoord(jp.y)}" r="2.6" fill="$wireHex"/>""")
      }
      if (showNetLabels) {
        val label = if (rNet.net.width > 1) "${rNet.net.name}[${rNet.net.width}]" else rNet.net.name
        sb.appendLine(
          """    <text x="${formatCoord(rNet.labelPosition.x)}" y="${formatCoord(rNet.labelPosition.y)}" fill="$wireHex" font-family="monospace" font-size="8" text-anchor="middle">${escapeXml(label.take(18))}</text>"""
        )
      }
    }

    // 2. Orange rectangular standard-cell blocks & contrasting I/O port markers
    layout.placedCells.values.forEach { placed ->
      val cell = placed.cell
      val x = placed.x
      val y = placed.y
      val cw = placed.width
      val ch = placed.height

      val (fillHex, strokeHex, labelHex) = when (cell.category) {
        SchematicCellCategory.INPUT_PORT -> Triple("#0B1F24", "#44CCEE", "#77EEFF")
        SchematicCellCategory.OUTPUT_PORT -> Triple("#241F0A", "#E8C547", "#FFE680")
        SchematicCellCategory.MODULE -> Triple("#261505", "#FFAA22", "#FFD077")
        else -> Triple("#221003", "#FF8822", "#FFB366")
      }

      sb.appendLine(
        """    <rect x="${formatCoord(x)}" y="${formatCoord(y)}" width="${formatCoord(cw)}" height="${formatCoord(ch)}" fill="$fillHex" stroke="$strokeHex" stroke-width="1.4"/>"""
      )

      if (cell.category.isModule) {
        sb.appendLine(
          """    <rect x="${formatCoord(x + 3f)}" y="${formatCoord(y + 3f)}" width="${formatCoord(cw - 6f)}" height="${formatCoord(ch - 6f)}" fill="none" stroke="$strokeHex" stroke-width="0.8"/>"""
        )
      }

      // Instance name above cell
      if (!cell.category.isPort) {
        sb.appendLine(
          """    <text x="${formatCoord(x)}" y="${formatCoord(y - 4f)}" fill="#C4C9D4" font-family="monospace" font-size="8">${escapeXml(cell.instanceName.take(22))}</text>"""
        )
      }

      // Cell type inside block
      val centerLabel = if (cell.category.isPort) cell.instanceName else cell.cellType
      sb.appendLine(
        """    <text x="${formatCoord(x + cw / 2f)}" y="${formatCoord(y + ch / 2f + 3f)}" fill="$labelHex" font-family="monospace" font-size="9" font-weight="bold" text-anchor="middle">${escapeXml(centerLabel.take(16))}</text>"""
      )

      // Input pins (left) & Output pins (right)
      placed.inputPinOffsets.forEachIndexed { idx, pinPt ->
        val pinName = cell.inputPins.getOrNull(idx)?.pinName.orEmpty()
        sb.appendLine("""    <rect x="${formatCoord(pinPt.x - 2f)}" y="${formatCoord(pinPt.y - 2f)}" width="4" height="4" fill="#FFCC44"/>""")
        if (!cell.category.isPort && pinName.isNotEmpty()) {
          sb.appendLine("""    <text x="${formatCoord(pinPt.x + 5f)}" y="${formatCoord(pinPt.y + 3f)}" fill="#AAEEBB" font-family="monospace" font-size="7">${escapeXml(pinName)}</text>""")
        }
      }
      placed.outputPinOffsets.forEachIndexed { idx, pinPt ->
        val pinName = cell.outputPins.getOrNull(idx)?.pinName.orEmpty()
        sb.appendLine("""    <rect x="${formatCoord(pinPt.x - 2f)}" y="${formatCoord(pinPt.y - 2f)}" width="4" height="4" fill="#55CC77"/>""")
        if (!cell.category.isPort && pinName.isNotEmpty()) {
          sb.appendLine("""    <text x="${formatCoord(pinPt.x - 5f)}" y="${formatCoord(pinPt.y + 3f)}" fill="#AAEEBB" font-family="monospace" font-size="7" text-anchor="end">${escapeXml(pinName)}</text>""")
        }
      }
    }

    sb.appendLine("  </g>")
    sb.appendLine("</svg>")
    return sb.toString()
  }

  fun buildPngBytes(
    layout: RoutedSchematicLayout,
    showNetLabels: Boolean = true,
  ): ByteArray {
    val headerH = 34
    val baseW = ceil(layout.boundsWidth.toDouble()).toInt().coerceIn(720, 4096)
    val baseH = ceil(layout.boundsHeight.toDouble()).toInt().coerceIn(460, 4096)
    val scale = if (baseW * 2 <= 4096 && baseH * 2 <= 4096) 2f else 1f
    val bmpW = (baseW * scale).toInt()
    val bmpH = ((baseH + headerH) * scale).toInt()

    val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(scale, scale)

    // Pure black background
    canvas.drawColor(AndroidColor.BLACK)

    val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = AndroidColor.rgb(0x55, 0xCC, 0x77)
      strokeWidth = 1.4f
    }
    val critWirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = AndroidColor.rgb(0xFF, 0x66, 0x55)
      strokeWidth = 1.8f
    }
    val junctionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = AndroidColor.rgb(0x55, 0xCC, 0x77)
    }
    val cellFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
    }
    val cellStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 1.5f
    }
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.MONOSPACE
      textSize = 9f
    }

    // Header
    textPaint.color = AndroidColor.rgb(0x55, 0xCC, 0x77)
    textPaint.textSize = 11f
    canvas.drawText(
      "${layout.graph.topModuleName} :: ${layout.graph.scopeTitle} | ${layout.placedCells.size} cells | ${layout.routedNets.size} nets",
      14f,
      20f,
      textPaint,
    )
    canvas.translate(0f, headerH.toFloat())

    // Wires
    layout.routedNets.forEach { rNet ->
      val p = if (rNet.net.isCritical) critWirePaint else wirePaint
      rNet.branches.forEach { branch ->
        for (i in 0 until branch.points.lastIndex) {
          val a = branch.points[i]
          val b = branch.points[i + 1]
          canvas.drawLine(a.x, a.y, b.x, b.y, p)
        }
      }
      junctionPaint.color = p.color
      rNet.junctionPoints.forEach { jp ->
        canvas.drawCircle(jp.x, jp.y, 2.5f, junctionPaint)
      }
      if (showNetLabels) {
        textPaint.color = p.color
        textPaint.textSize = 7.5f
        textPaint.textAlign = Paint.Align.CENTER
        val lbl = if (rNet.net.width > 1) "${rNet.net.name}[${rNet.net.width}]" else rNet.net.name
        canvas.drawText(lbl.take(18), rNet.labelPosition.x, rNet.labelPosition.y, textPaint)
      }
    }

    // Cells
    layout.placedCells.values.forEach { placed ->
      val cell = placed.cell
      when (cell.category) {
        SchematicCellCategory.INPUT_PORT -> {
          cellFillPaint.color = AndroidColor.rgb(0x0B, 0x1F, 0x24)
          cellStrokePaint.color = AndroidColor.rgb(0x44, 0xCC, 0xEE)
          textPaint.color = AndroidColor.rgb(0x77, 0xEE, 0xFF)
        }
        SchematicCellCategory.OUTPUT_PORT -> {
          cellFillPaint.color = AndroidColor.rgb(0x24, 0x1F, 0x0A)
          cellStrokePaint.color = AndroidColor.rgb(0xE8, 0xC5, 0x47)
          textPaint.color = AndroidColor.rgb(0xFF, 0xE6, 0x80)
        }
        else -> {
          cellFillPaint.color = AndroidColor.rgb(0x22, 0x10, 0x03)
          cellStrokePaint.color = AndroidColor.rgb(0xFF, 0x88, 0x22)
          textPaint.color = AndroidColor.rgb(0xFF, 0xB3, 0x66)
        }
      }

      canvas.drawRect(placed.x, placed.y, placed.x + placed.width, placed.y + placed.height, cellFillPaint)
      canvas.drawRect(placed.x, placed.y, placed.x + placed.width, placed.y + placed.height, cellStrokePaint)

      if (!cell.category.isPort) {
        textPaint.color = AndroidColor.rgb(0xC4, 0xC9, 0xD4)
        textPaint.textSize = 7.5f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(cell.instanceName.take(20), placed.x, placed.y - 3f, textPaint)
      }

      textPaint.color = when (cell.category) {
        SchematicCellCategory.INPUT_PORT -> AndroidColor.rgb(0x77, 0xEE, 0xFF)
        SchematicCellCategory.OUTPUT_PORT -> AndroidColor.rgb(0xFF, 0xE6, 0x80)
        else -> AndroidColor.rgb(0xFF, 0xB3, 0x66)
      }
      textPaint.textSize = 9f
      textPaint.textAlign = Paint.Align.CENTER
      val label = if (cell.category.isPort) cell.instanceName else cell.cellType
      canvas.drawText(label.take(16), placed.x + placed.width / 2f, placed.y + placed.height / 2f + 3f, textPaint)

      placed.inputPinOffsets.forEach { pt ->
        pinPaint.color = AndroidColor.rgb(0xFF, 0xCC, 0x44)
        canvas.drawRect(pt.x - 2f, pt.y - 2f, pt.x + 2f, pt.y + 2f, pinPaint)
      }
      placed.outputPinOffsets.forEach { pt ->
        pinPaint.color = AndroidColor.rgb(0x55, 0xCC, 0x77)
        canvas.drawRect(pt.x - 2f, pt.y - 2f, pt.x + 2f, pt.y + 2f, pinPaint)
      }
    }

    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    return out.toByteArray()
  }

  fun shareSchematicFile(
    context: Context,
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
    onResult: (String) -> Unit,
  ) {
    runCatching {
      val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
      val file = File(exportDir, fileName).apply { writeBytes(bytes) }
      val authority = "${context.packageName}.fileprovider"
      val uri = FileProvider.getUriForFile(context, authority, file)
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(Intent.createChooser(intent, "Export $fileName"))
    }.onSuccess {
      onResult("Exported $fileName (${bytes.size} B).")
    }.onFailure { err ->
      onResult("Schematic export failed: ${err.localizedMessage ?: "error"}")
    }
  }

  private fun formatCoord(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

  private fun escapeXml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
}
