package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.domain.synth.PhysicalLayoutSnapshot
import com.example.domain.synth.SynthesisReport
import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExportShareHelper {

  fun buildTapeoutZipBytes(
    projectFiles: List<RtlFileEntry>,
    fallbackRtl: String,
    report: SynthesisReport,
    layout: PhysicalLayoutSnapshot?,
    sdcText: String,
  ): ByteArray {
    val base = (layout?.moduleName ?: report.moduleName)
      .replace(Regex("[^A-Za-z0-9_]"), "_")
      .ifBlank { "rtl_design" }

    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
      fun putText(entryName: String, content: String) {
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
      }

      fun putBytes(entryName: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(bytes)
        zos.closeEntry()
      }

      // 1. RTL & Testbench Sources
      val rtlSources = projectFiles.filter {
        it.language == RtlLanguage.VERILOG ||
          it.language == RtlLanguage.SYSTEM_VERILOG ||
          it.language == RtlLanguage.VHDL ||
          it.language == RtlLanguage.TESTBENCH
      }
      if (rtlSources.isEmpty()) {
        putText("rtl/${base}.v", fallbackRtl)
      } else {
        val seenNames = mutableSetOf<String>()
        rtlSources.forEach { file ->
          val cleanName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "${base}.v" }
          val unique = if (seenNames.add(cleanName)) cleanName else "${file.id}_$cleanName"
          putText("rtl/$unique", file.content)
        }
      }

      // 2. Synthesized Gate-Level Netlist
      putText("netlist/${base}_synth.v", report.netlistVerilog)

      // 3. SDC Timing Constraints
      val effectiveSdc = sdcText.ifBlank {
        "create_clock -name clk -period ${"%.3f".format(Locale.US, report.clockPeriodNs)} [get_ports clk]\n"
      }
      putText("constraints/${base}.sdc", effectiveSdc)

      // 4. STA & Power Report
      val pwr = report.powerAnalysis
      val rptText = buildString {
        appendLine("==================================================================")
        appendLine("  RTL Synth Studio — Signoff STA, Area & Power Report")
        appendLine("==================================================================")
        appendLine("Module          : ${report.moduleName}")
        appendLine("Target Library  : ${report.targetLibrary.displayName} (${report.targetLibrary.processNode})")
        appendLine("Engine Profile  : ${report.engineProfile.displayName}")
        appendLine("Opt Goal        : ${report.optimizationGoal.displayName}")
        appendLine("Clock Target    : ${"%.3f".format(Locale.US, report.clockPeriodNs)} ns (${"%.2f".format(Locale.US, 1000.0 / report.clockPeriodNs)} MHz)")
        appendLine("Critical Path   : ${"%.3f".format(Locale.US, report.criticalPathDelayNs)} ns")
        appendLine("Fmax            : ${"%.2f".format(Locale.US, report.fmaxMhz)} MHz")
        appendLine("Setup WNS / TNS : ${"%.3f".format(Locale.US, report.wnsNs)} ns / ${"%.3f".format(Locale.US, report.tnsNs)} ns")
        appendLine("Total Cells     : ${report.cellCount} (Comb: ${report.combCellCount}, Seq: ${report.seqCellCount})")
        appendLine("Standard Area   : ${"%.2f".format(Locale.US, report.totalAreaUm2)} um^2")
        appendLine("Total Power     : ${"%.3f".format(Locale.US, report.totalPowerMw)} mW (Dyn: ${"%.3f".format(Locale.US, report.dynamicPowerMw)}, Leak: ${"%.3f".format(Locale.US, report.leakagePowerMw)})")
        if (pwr != null) {
          appendLine("Power Breakdown : Switching=${"%.4f".format(Locale.US, pwr.switchingPowerMw)} mW | Internal=${"%.4f".format(Locale.US, pwr.internalPowerMw)} mW | Leakage=${"%.4f".format(Locale.US, pwr.leakagePowerMw)} mW")
          appendLine("Activity Source : ${pwr.activitySource.label} | Avg alpha=${"%.3f".format(Locale.US, pwr.averageActivityFactor)} | Energy/Cycle=${"%.2f".format(Locale.US, pwr.energyPerCyclePj)} pJ")
          appendLine("Power Domains   : Comb=${"%.4f".format(Locale.US, pwr.combinationalPowerMw)} mW | Seq=${"%.4f".format(Locale.US, pwr.sequentialPowerMw)} mW | ClockTree=${"%.4f".format(Locale.US, pwr.clockTreePowerMw)} mW")
        }
        if (layout != null) {
          appendLine("Die / Core      : ${"%.1f".format(Locale.US, layout.dieWidthUm)} x ${"%.1f".format(Locale.US, layout.dieHeightUm)} um (Util: ${"%.1f".format(Locale.US, layout.actualUtilizationPercent)}%)")
          appendLine("Clock Skew      : ${"%.3f".format(Locale.US, layout.clockSkewNs)} ns | Hold WNS: ${"%.3f".format(Locale.US, layout.holdWnsNs)} ns")
          appendLine("Total Wirelen   : ${"%.1f".format(Locale.US, layout.totalWirelengthUm)} um | Vias: ${layout.viaCount}")
          appendLine("DRC / LVS       : ${layout.drcSummary} | LVS=${if (layout.lvsClean) "CLEAN" else "MISMATCH"}")
          appendLine("GDSII SHA-256   : ${layout.gdsSha256}")
        }
        appendLine()
        appendLine("--- Critical Timing Path ---")
        report.criticalPath.forEachIndexed { idx, step ->
          appendLine(
            "  [${idx + 1}] ${step.instanceName} (${step.cellName}) -> net=${step.netName} " +
              "+${"%.3f".format(Locale.US, step.incrDelayNs)}ns  @ ${"%.3f".format(Locale.US, step.arrivalNs)}ns" +
              if (step.sourceLine > 0) " (L${step.sourceLine})" else ""
          )
        }
        if (pwr != null && pwr.topPowerNodes.isNotEmpty()) {
          appendLine()
          appendLine("--- Top Power Cells (uW) ---")
          pwr.topPowerNodes.take(8).forEach { node ->
            appendLine(
              "  ${node.instanceName} (${node.cellName}) alpha=${"%.2f".format(Locale.US, node.activityFactor)} | " +
                "Sw=${"%.2f".format(Locale.US, node.switchingPowerUw)} Int=${"%.2f".format(Locale.US, node.internalPowerUw)} Leak=${"%.2f".format(Locale.US, node.leakagePowerUw)} Total=${"%.2f".format(Locale.US, node.totalPowerUw)} uW"
            )
          }
        }
      }
      putText("reports/${base}_sta_power.rpt", rptText)

      // 5. Physical PnR / KLayout Artifacts
      if (layout != null) {
        if (layout.tclScript.isNotBlank()) {
          putText("scripts/${base}_openroad.tcl", layout.tclScript)
        }
        if (layout.gdsBytes.isNotEmpty()) {
          putBytes("gds/${base}.gds", layout.gdsBytes)
        }
        if (layout.klayoutLypXml.isNotBlank()) {
          putText("gds/${base}.lyp", layout.klayoutLypXml)
        }
        if (layout.klayoutSnapshotPng.isNotEmpty()) {
          putBytes("gds/${base}_klayout.png", layout.klayoutSnapshotPng)
        }
      }

      // 5b. DFT Scan Insertion, STIL ATPG & SCANDEF Artifacts
      val dft = report.dftReport
      if (dft != null) {
        if (dft.dftSummaryReport.isNotBlank()) {
          putText("dft/${base}_dft.rpt", dft.dftSummaryReport)
        }
        if (dft.stilPatternText.isNotBlank()) {
          putText("dft/${base}_atpg.stil", dft.stilPatternText)
        }
        if (dft.scanInserted && dft.scanDefText.isNotBlank()) {
          putText("dft/${base}_scanchains.def", dft.scanDefText)
        }
      }

      // 5c. Pre-Synthesis RTL DRC Report
      report.rtlDrcReport?.let { rtlDrc ->
        putText("verification/${base}_rtl_drc.rpt", rtlDrc.formatTextReport())
      }

      // 6. Manifest Metadata
      val manifestJson = buildString {
        appendLine("{")
        appendLine("  \"module\": \"${report.moduleName}\",")
        appendLine("  \"library\": \"${report.targetLibrary.name}\",")
        appendLine("  \"goal\": \"${report.optimizationGoal.name}\",")
        appendLine("  \"clockPeriodNs\": ${"%.4f".format(Locale.US, report.clockPeriodNs)},")
        appendLine("  \"fmaxMhz\": ${"%.2f".format(Locale.US, report.fmaxMhz)},")
        appendLine("  \"wnsNs\": ${"%.4f".format(Locale.US, report.wnsNs)},")
        appendLine("  \"areaUm2\": ${"%.2f".format(Locale.US, report.totalAreaUm2)},")
        appendLine("  \"cellCount\": ${report.cellCount},")
        appendLine("  \"switchingPowerMw\": ${"%.4f".format(Locale.US, pwr?.switchingPowerMw ?: report.switchingPowerMw)},")
        appendLine("  \"internalPowerMw\": ${"%.4f".format(Locale.US, pwr?.internalPowerMw ?: report.internalPowerMw)},")
        appendLine("  \"leakagePowerMw\": ${"%.4f".format(Locale.US, pwr?.leakagePowerMw ?: report.leakagePowerMw)},")
        appendLine("  \"totalPowerMw\": ${"%.4f".format(Locale.US, report.totalPowerMw)},")
        appendLine("  \"averageActivityFactor\": ${"%.4f".format(Locale.US, pwr?.averageActivityFactor ?: 0.2)},")
        appendLine("  \"dftScanInserted\": ${dft?.scanInserted ?: false},")
        appendLine("  \"stuckAtCoveragePct\": ${"%.2f".format(Locale.US, dft?.stuckAtCoveragePct ?: 0.0)},")
        appendLine("  \"hasPhysicalGds\": ${layout != null},")
        appendLine("  \"gdsSha256\": \"${layout?.gdsSha256.orEmpty()}\"")
        appendLine("}")
      }
      putText("manifest.json", manifestJson)
    }
    return baos.toByteArray()
  }

  fun shareZipBundle(
    context: Context,
    moduleName: String,
    zipBytes: ByteArray,
    onResult: (String) -> Unit,
  ) {
    runCatching {
      val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
      val base = moduleName.replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "rtl_design" }
      val zipFile = File(exportDir, "${base}_tapeout_bundle.zip").apply { writeBytes(zipBytes) }
      val authority = "${context.packageName}.fileprovider"
      val uri = FileProvider.getUriForFile(context, authority, zipFile)
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "${base} Tapeout Bundle (.zip)")
        putExtra(Intent.EXTRA_TEXT, "Complete RTL, synthesized netlist, SDC, STA report, OpenROAD Tcl, GDSII, and KLayout PNG/LYP archive for $base.")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(Intent.createChooser(intent, "Share ${base}_tapeout_bundle.zip"))
    }.onSuccess {
      onResult("Opened share sheet for ${moduleName} tapeout ZIP (${zipBytes.size} B).")
    }.onFailure { err ->
      onResult("ZIP share failed: ${err.localizedMessage ?: "error"}")
    }
  }

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
