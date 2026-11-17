package com.example.ui.viewmodel

import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import com.example.domain.synth.RtlPresetTemplate
import com.example.domain.synth.SdcParser
import com.example.domain.synth.VerilogHdlParser
import com.example.domain.synth.VerilogParseResult
import com.example.domain.synth.VhdlConversionResult
import com.example.domain.synth.VhdlFrontendParser

internal object RtlProjectHelpers {

  fun parseLanguageAware(source: String, selectedTopModule: String?): VerilogParseResult {
    val effective = if (VhdlFrontendParser.looksLikeVhdl(source)) {
      when (val conv = VhdlFrontendParser.toCanonicalVerilog(source)) {
        is VhdlConversionResult.Success -> conv.verilogSource
        is VhdlConversionResult.Error -> {
          return VerilogParseResult.Error(line = conv.line, column = 1, message = conv.message)
        }
      }
    } else {
      source
    }
    return VerilogHdlParser.parse(effective, selectedTopModule)
  }

  fun buildDefaultFiles(
    designName: String,
    rtlCode: String,
    language: RtlLanguage,
    sdcText: String,
    testbenchCode: String = "",
  ): List<RtlFileEntry> {
    val cleanBase = designName.trim().ifBlank { "rtl_module" }
    val rtlExt = language.extension
    val rtlFile = RtlFileEntry(
      id = "file_rtl_top",
      name = "$cleanBase.$rtlExt",
      language = language,
      content = rtlCode,
    )
    val sdcFile = RtlFileEntry(
      id = "file_sdc",
      name = "constraints.sdc",
      language = RtlLanguage.SDC,
      content = sdcText,
    )
    val tbContent = testbenchCode.ifBlank { defaultTestbenchFor(cleanBase) }
    val tbFile = RtlFileEntry(
      id = "file_tb",
      name = "tb_${cleanBase}.v",
      language = RtlLanguage.TESTBENCH,
      content = tbContent,
    )
    return listOf(rtlFile, sdcFile, tbFile)
  }

  fun buildFilesFromPreset(template: RtlPresetTemplate, sdcText: String): List<RtlFileEntry> {
    return buildDefaultFiles(
      designName = template.name,
      rtlCode = template.verilogCode,
      language = template.language,
      sdcText = sdcText,
      testbenchCode = template.testbenchCode,
    )
  }

  fun defaultTestbenchFor(moduleName: String): String {
    val clean = moduleName.trim().ifBlank { "rtl_module" }
    return """
      // Testbench stimulus for $clean
      `timescale 1ns/1ps
      module tb_$clean;
        reg clk = 0;
        reg rst = 1;
        reg en = 1;

        always #1 clk = ~clk;

        initial begin
          rst = 1; en = 0;
          #2 rst = 0; en = 1;
        end
      endmodule
    """.trimIndent()
  }

  fun detectPrimaryRtlLanguage(code: String, explicitName: String? = null): RtlLanguage {
    if (explicitName != null) {
      val byExt = RtlLanguage.fromFileName(explicitName)
      if (byExt == RtlLanguage.SYSTEM_VERILOG || byExt == RtlLanguage.VHDL) return byExt
    }
    if (VhdlFrontendParser.looksLikeVhdl(code)) return RtlLanguage.VHDL
    if (Regex("""\b(logic|always_ff|always_comb|always_latch|typedef|enum|interface)\b""").containsMatchIn(code)) {
      return RtlLanguage.SYSTEM_VERILOG
    }
    return RtlLanguage.VERILOG
  }

  data class CombinedSourceSegment(
    val fileId: String,
    val fileName: String,
    val startLine: Int,
    val endLine: Int,
  )

  data class CombinedRtlBundle(
    val combinedSource: String,
    val segments: List<CombinedSourceSegment>,
    val resolvedIncludes: List<String>,
  )

  private val includeDirectiveRegex = Regex("""^\s*`include\s+"([^"]+)".*$""", RegexOption.MULTILINE)

  fun combineProjectRtlSources(files: List<RtlFileEntry>, fallbackRtl: String): String =
    bundleProjectSources(files, fallbackRtl).combinedSource

  fun bundleProjectSources(files: List<RtlFileEntry>, fallbackRtl: String): CombinedRtlBundle {
    val rtlFiles = files.filter {
      it.language == RtlLanguage.VERILOG ||
        it.language == RtlLanguage.SYSTEM_VERILOG ||
        it.language == RtlLanguage.VHDL
    }
    if (rtlFiles.isEmpty()) {
      val lineCount = fallbackRtl.lines().size.coerceAtLeast(1)
      return CombinedRtlBundle(
        combinedSource = fallbackRtl,
        segments = listOf(CombinedSourceSegment("file_rtl_top", "top.v", 1, lineCount)),
        resolvedIncludes = emptyList(),
      )
    }

    val byNameLower = rtlFiles.associateBy { it.name.trim().lowercase() }
    val byBaseNameLower = rtlFiles.associateBy { it.name.substringAfterLast('/').trim().lowercase() }
    val resolvedIncludes = mutableSetOf<String>()

    fun resolveIncludes(
      content: String,
      visited: MutableSet<String>,
    ): String {
      return includeDirectiveRegex.replace(content) { match ->
        val rawPath = match.groupValues[1].trim()
        val key = rawPath.substringAfterLast('/').lowercase()
        val includedFile = byNameLower[rawPath.lowercase()] ?: byBaseNameLower[key]
        if (includedFile == null) {
          "// [unresolved `include \"$rawPath\"]"
        } else if (!visited.add(includedFile.name.lowercase())) {
          "// [already included: \"$rawPath\"]"
        } else {
          resolvedIncludes += includedFile.name
          val body = resolveIncludes(includedFile.content, visited)
          "// --- `include \"${includedFile.name}\" ---\n$body\n// --- end `include \"${includedFile.name}\" ---"
        }
      }
    }

    if (rtlFiles.size == 1) {
      val only = rtlFiles.first()
      val expanded = if (only.language == RtlLanguage.VHDL) only.content else resolveIncludes(only.content, mutableSetOf(only.name.lowercase()))
      val lineCount = expanded.lines().size.coerceAtLeast(1)
      return CombinedRtlBundle(
        combinedSource = expanded,
        segments = listOf(CombinedSourceSegment(only.id, only.name, 1, lineCount)),
        resolvedIncludes = resolvedIncludes.toList(),
      )
    }

    val primary = rtlFiles.firstOrNull { it.id == "file_rtl_top" } ?: rtlFiles.first()
    if (primary.language == RtlLanguage.VHDL) {
      val lineCount = primary.content.lines().size.coerceAtLeast(1)
      return CombinedRtlBundle(
        combinedSource = primary.content,
        segments = listOf(CombinedSourceSegment(primary.id, primary.name, 1, lineCount)),
        resolvedIncludes = emptyList(),
      )
    }

    // Order: header files (.svh/.vh) excluded if already inlined via `include,
    // packages/interfaces next, then submodules, then primary/top module last.
    fun filePriority(f: RtlFileEntry): Int {
      val low = f.name.lowercase()
      val body = f.content.lowercase()
      return when {
        low.endsWith(".svh") || low.endsWith(".vh") -> 0
        body.contains("package ") || body.contains("interface ") -> 1
        f.id == primary.id -> 3
        else -> 2
      }
    }

    val sb = StringBuilder()
    val segments = mutableListOf<CombinedSourceSegment>()
    var currentLine = 1

    val orderedFiles = rtlFiles.sortedBy { filePriority(it) }
    orderedFiles.forEach { file ->
      val isHeader = file.name.lowercase().endsWith(".svh") || file.name.lowercase().endsWith(".vh")
      if (isHeader && file.name in resolvedIncludes) {
        return@forEach
      }
      val expanded = resolveIncludes(file.content, mutableSetOf(file.name.lowercase()))
      if (sb.isNotEmpty()) {
        sb.append("\n\n")
        currentLine += 2
      }
      val lineCount = expanded.lines().size.coerceAtLeast(1)
      segments += CombinedSourceSegment(
        fileId = file.id,
        fileName = file.name,
        startLine = currentLine,
        endLine = currentLine + lineCount - 1,
      )
      sb.append(expanded)
      currentLine += lineCount - 1
    }

    return CombinedRtlBundle(
      combinedSource = sb.toString(),
      segments = segments,
      resolvedIncludes = resolvedIncludes.toList(),
    )
  }

  /**
   * Maps a 1-based line number from [bundleProjectSources] back to the originating tab's
   * fileId and local 1-based line number.
   */
  fun mapCombinedLineToFile(
    bundle: CombinedRtlBundle,
    combinedLine: Int,
  ): Pair<String, Int>? {
    if (bundle.segments.isEmpty()) return null
    val seg = bundle.segments.firstOrNull { combinedLine in it.startLine..it.endLine }
      ?: bundle.segments.lastOrNull { combinedLine >= it.startLine }
      ?: bundle.segments.first()
    val localLine = (combinedLine - seg.startLine + 1).coerceAtLeast(1)
    return seg.fileId to localLine
  }

  fun findSdcContent(files: List<RtlFileEntry>, fallbackSdc: String, moduleName: String, clockPeriodNs: Double): String {
    val sdc = files.firstOrNull { it.language == RtlLanguage.SDC }?.content
    if (!sdc.isNullOrBlank()) return sdc
    if (fallbackSdc.isNotBlank()) return fallbackSdc
    val parsed = (parseLanguageAware(fallbackSdc, null) as? VerilogParseResult.Success)?.design
    return SdcParser.generateDefaultSdc(moduleName, parsed?.topModule?.ports.orEmpty(), clockPeriodNs)
  }
}
