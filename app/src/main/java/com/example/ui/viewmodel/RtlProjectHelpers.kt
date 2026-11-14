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

  fun combineProjectRtlSources(files: List<RtlFileEntry>, fallbackRtl: String): String {
    val rtlFiles = files.filter {
      it.language == RtlLanguage.VERILOG ||
        it.language == RtlLanguage.SYSTEM_VERILOG ||
        it.language == RtlLanguage.VHDL
    }
    if (rtlFiles.isEmpty()) return fallbackRtl
    if (rtlFiles.size == 1) return rtlFiles.first().content
    val primary = rtlFiles.firstOrNull { it.id == "file_rtl_top" } ?: rtlFiles.first()
    if (primary.language == RtlLanguage.VHDL) return primary.content
    return rtlFiles.joinToString("\n\n") { it.content }
  }

  fun findSdcContent(files: List<RtlFileEntry>, fallbackSdc: String, moduleName: String, clockPeriodNs: Double): String {
    val sdc = files.firstOrNull { it.language == RtlLanguage.SDC }?.content
    if (!sdc.isNullOrBlank()) return sdc
    if (fallbackSdc.isNotBlank()) return fallbackSdc
    val parsed = (parseLanguageAware(fallbackSdc, null) as? VerilogParseResult.Success)?.design
    return SdcParser.generateDefaultSdc(moduleName, parsed?.topModule?.ports.orEmpty(), clockPeriodNs)
  }
}
