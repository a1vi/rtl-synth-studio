package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.domain.synth.PhysicalLayoutSnapshot
import com.example.domain.synth.SynthesisReport
import java.io.File
import java.util.Locale

object ExportShareHelper {

  fun shareBuildBundle(
    context: Context,
    report: SynthesisReport,
    layout: PhysicalLayoutSnapshot?,
    onResult: (String) -> Unit,
  ) {
    runCatching {
      val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
      val base = (layout?.moduleName ?: report.moduleName)
        .replace(Regex("[^A-Za-z0-9_]"), "_")
        .ifBlank { "rtl_design" }

      val uris = arrayListOf<Uri>()
      val authority = "${context.packageName}.fileprovider"

      // 1. Netlist (.v)
      val netlistFile = File(exportDir, "${base}_synth.v").apply {
        writeText(report.netlistVerilog, Charsets.UTF_8)
      }
      uris += FileProvider.getUriForFile(context, authority, netlistFile)

      // 2. PPA & Physical Report (.txt)
      val reportText = buildString {
        appendLine("=== RTL Synth Studio Engineering Bundle ===")
        appendLine("Module: ${report.moduleName}")
        appendLine("PDK: ${report.targetLibrary.displayName}")
        appendLine("Cells: ${report.cellCount} (Comb ${report.combCellCount} / Seq ${report.seqCellCount})")
        appendLine("Area: ${"%.2f".format(Locale.US, report.totalAreaUm2)} um^2")
        appendLine("Fmax: ${"%.2f".format(Locale.US, report.fmaxMhz)} MHz | Setup WNS: ${report.wnsNs} ns")
        appendLine("Power: ${"%.3f".format(Locale.US, report.totalPowerMw)} mW")
        if (layout != null) {
          appendLine("Die: ${"%.1f".format(Locale.US, layout.dieWidthUm)} x ${"%.1f".format(Locale.US, layout.dieHeightUm)} um")
          appendLine("Core Util: ${layout.coreUtilizationPercent}% (Actual ${"%.1f".format(Locale.US, layout.actualUtilizationPercent)}%)")
          appendLine("Clock Skew: ${"%.3f".format(Locale.US, layout.clockSkewNs)} ns | Hold WNS: ${"%.3f".format(Locale.US, layout.holdWnsNs)} ns")
          appendLine("Wirelength: ${"%.1f".format(Locale.US, layout.totalWirelengthUm)} um | Vias: ${layout.viaCount}")
          appendLine("DRC/LVS: ${layout.drcSummary}")
          appendLine("GDSII Size: ${layout.gdsBytes.size} bytes (SHA-256: ${layout.gdsSha256})")
        }
      }
      val reportFile = File(exportDir, "${base}_report.txt").apply {
        writeText(reportText, Charsets.UTF_8)
      }
      uris += FileProvider.getUriForFile(context, authority, reportFile)

      if (layout != null) {
        // 3. Binary GDSII (.gds)
        val gdsFile = File(exportDir, "${base}.gds").apply {
          writeBytes(layout.gdsBytes)
        }
        uris += FileProvider.getUriForFile(context, authority, gdsFile)

        // 4. OpenROAD / Yosys / Magic Tcl script (.tcl)
        val tclFile = File(exportDir, "${base}_flow.tcl").apply {
          writeText(layout.tclScript, Charsets.UTF_8)
        }
        uris += FileProvider.getUriForFile(context, authority, tclFile)

        // 5. KLayout Layer Properties (.lyp)
        if (layout.klayoutLypXml.isNotBlank()) {
          val lypFile = File(exportDir, "${base}.lyp").apply {
            writeText(layout.klayoutLypXml, Charsets.UTF_8)
          }
          uris += FileProvider.getUriForFile(context, authority, lypFile)
        }

        // 6. KLayout-palette GDSII Snapshot PNG
        if (layout.klayoutSnapshotPng.isNotEmpty()) {
          val pngFile = File(exportDir, "${base}_klayout.png").apply {
            writeBytes(layout.klayoutSnapshotPng)
          }
          uris += FileProvider.getUriForFile(context, authority, pngFile)
        }
      }

      val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, "$base RTL-to-GDSII KLayout Bundle")
        putExtra(Intent.EXTRA_TEXT, "Synthesized netlist, Tcl script, PPA report, binary .gds, KLayout .lyp, and PNG snapshot for $base.")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(Intent.createChooser(shareIntent, "Share $base GDSII & Reports"))
    }.onSuccess {
      onResult("Opened Android share sheet for ${report.moduleName}.")
    }.onFailure { err ->
      onResult("Share failed: ${err.localizedMessage ?: "error"}")
    }
  }
}
