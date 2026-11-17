package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import com.example.data.model.TargetLibrary
import com.example.ui.components.ExportShareHelper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DftEngineTest {

  private val multiStageSeqRtl = """
    module dual_stage_pipe(
      input clk,
      input rst,
      input [3:0] a,
      input [3:0] b,
      input sel,
      output reg [3:0] q
    );
      reg [3:0] stage1;
      always @(posedge clk or posedge rst) begin
        if (rst) begin
          stage1 <= 4'd0;
          q <= 4'd0;
        end else begin
          stage1 <= sel ? (a + b) : (a ^ b);
          q <= stage1 + 4'd1;
        end
      end
    endmodule
  """.trimIndent()

  @Test
  fun scanInsertion_convertsSeqToSdffEmitsStilScandefAndJtagMbist() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = multiStageSeqRtl,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
      dftConfig = DftConfig(
        scanInsertionEnabled = true,
        scanStyle = DftScanStyle.MUXED_D,
        targetChainCount = 1,
        maxChainLength = 16,
        insertLockupLatches = true,
        insertJtagBoundaryTap = true,
        insertMbistCollar = true,
        atpgMaxPatterns = 32,
      ),
    ) as SynthesisResult.Success

    val report = result.report
    val dft = report.dftReport
    assertNotNull("Expected non-null DftReport", dft)
    dft!!

    assertTrue("Expected scanInserted=true", dft.scanInserted)
    assertTrue("Expected sequential elements replaced", dft.scanReplacedElements >= 2)
    assertTrue("Expected at least 1 scan chain", dft.scanChains.isNotEmpty())
    assertTrue("Expected lockup latch inserted on multi-stage chain", dft.lockupLatchCount >= 1)
    assertTrue("Expected JTAG TAP inserted", dft.jtagTapInserted)
    assertTrue("Expected MBIST collar inserted", dft.mbistCollarInserted)
    assertTrue("Expected positive scan area overhead", dft.scanAreaOverheadUm2 > 0.0)

    // Verify mapped nodes contain SDFF/SDFFR & DLAT
    val hasScanCell = report.nodes.any { it.gateType == GateType.SDFF || it.gateType == GateType.SDFFR }
    assertTrue("Expected SDFF or SDFFR nodes in netlist", hasScanCell)
    assertTrue(
      "Expected LOCKUP_LATCH node in netlist",
      report.nodes.any { it.gateType == GateType.LOCKUP_LATCH },
    )

    // Verify structural Verilog contains DFT ports & .SE / .SI pins
    val netlist = report.netlistVerilog
    assertTrue("Expected scan_en port in netlist", "scan_en" in netlist)
    assertTrue("Expected scan_in_0 port in netlist", "scan_in_0" in netlist)
    assertTrue("Expected scan_out_0 port in netlist", "scan_out_0" in netlist)
    assertTrue("Expected .SE pin in netlist", ".SE(" in netlist)
    assertTrue("Expected .SI pin in netlist", ".SI(" in netlist)
    assertTrue("Expected scan_out_0 tail assignment in netlist", "assign scan_out_0 =" in netlist)
    assertTrue("Expected IEEE 1149.1 tck/tdo ports", "tck" in netlist && "tdo" in netlist)
    assertTrue("Expected MBIST ports", "bist_en" in netlist && "bist_fail" in netlist)
    assertEquals(DftRuleSeverity.PASS, dft.dftRuleChecks.firstOrNull { it.ruleId == "DFT-06" }?.severity)

    // Verify STIL & SCANDEF formats
    assertTrue("Expected STIL 1.0 header", "STIL 1.0;" in dft.stilPatternText)
    assertTrue("Expected ScanStructures block in STIL", "ScanStructures" in dft.stilPatternText)
    assertTrue("Expected SCANCHAINS in SCANDEF", "SCANCHAINS" in dft.scanDefText)
  }

  @Test
  fun dftRuleChecker_flagsDerivedClockAndPassesCleanScanRules() {
    val gatedClockRtl = """
      module gated_clk_dut(
        input clk,
        input en,
        input d,
        output reg q
      );
        wire gclk;
        assign gclk = clk & en;
        always @(posedge gclk) begin
          q <= d;
        end
      endmodule
    """.trimIndent()

    val gatedRes = RtlSynthesisEngine.synthesize(
      verilogSource = gatedClockRtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
      dftConfig = DftConfig(scanInsertionEnabled = false),
    ) as SynthesisResult.Success

    val gatedDft = gatedRes.report.dftReport!!
    val dft01 = gatedDft.dftRuleChecks.firstOrNull { it.ruleId == "DFT-01" }
    assertNotNull("Expected DFT-01 rule check", dft01)
    assertEquals(DftRuleSeverity.WARNING, dft01?.severity)

    val cleanScanRes = RtlSynthesisEngine.synthesize(
      verilogSource = multiStageSeqRtl,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
      dftConfig = DftConfig(scanInsertionEnabled = true, insertLockupLatches = true),
    ) as SynthesisResult.Success

    val cleanDft = cleanScanRes.report.dftReport!!
    val dft04 = cleanDft.dftRuleChecks.first { it.ruleId == "DFT-04" }
    val dft05 = cleanDft.dftRuleChecks.first { it.ruleId == "DFT-05" }
    assertEquals(DftRuleSeverity.PASS, dft04.severity)
    assertEquals(DftRuleSeverity.PASS, dft05.severity)
  }

  @Test
  fun atpgFaultSimulation_achievesStrongCoverageWithScanEnabled() {
    val nonScanRes = RtlSynthesisEngine.synthesize(
      verilogSource = multiStageSeqRtl,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
      dftConfig = DftConfig(scanInsertionEnabled = false),
    ) as SynthesisResult.Success

    val scanRes = RtlSynthesisEngine.synthesize(
      verilogSource = multiStageSeqRtl,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
      dftConfig = DftConfig(scanInsertionEnabled = true, atpgMaxPatterns = 32),
    ) as SynthesisResult.Success

    val nonScanDft = nonScanRes.report.dftReport!!
    val scanDft = scanRes.report.dftReport!!

    assertTrue("Expected > 0 stuck-at faults extracted", scanDft.totalFaults > 0)
    assertTrue("Expected > 0 detected faults", scanDft.detectedFaults > 0)
    assertTrue("Expected >= 1 ATPG vector generated", scanDft.atpgVectors.isNotEmpty())
    assertTrue(
      "Expected scan coverage (${scanDft.stuckAtCoveragePct}%) >= non-scan (${nonScanDft.stuckAtCoveragePct}%)",
      scanDft.stuckAtCoveragePct >= nonScanDft.stuckAtCoveragePct,
    )
  }

  @Test
  fun tapeoutZipExport_includesDftReportStilAndScandef() {
    val res = RtlSynthesisEngine.synthesize(
      verilogSource = multiStageSeqRtl,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
      dftConfig = DftConfig(scanInsertionEnabled = true),
    ) as SynthesisResult.Success

    val zipBytes = ExportShareHelper.buildTapeoutZipBytes(
      projectFiles = listOf(RtlFileEntry("f1", "dual_stage_pipe.v", RtlLanguage.VERILOG, multiStageSeqRtl)),
      fallbackRtl = multiStageSeqRtl,
      report = res.report,
      layout = null,
      sdcText = "",
    )

    val entryNames = mutableSetOf<String>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        entryNames += entry.name
        entry = zis.nextEntry
      }
    }

    assertTrue("Expected DFT report in ZIP", entryNames.contains("dft/dual_stage_pipe_dft.rpt"))
    assertTrue("Expected STIL ATPG file in ZIP", entryNames.contains("dft/dual_stage_pipe_atpg.stil"))
    assertTrue("Expected SCANDEF file in ZIP", entryNames.contains("dft/dual_stage_pipe_scanchains.def"))
  }
}
