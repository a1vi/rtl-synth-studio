package com.example.domain.synth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import com.example.ui.components.ExportShareHelper
import com.example.ui.viewmodel.RtlProjectHelpers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoadmapTierFeaturesTest {

  @Test
  fun synthesisEngine_recursivelyFlattensSubmodulesWithPositionalAndNamedParamOverrides() {
    val hierarchicalRtl = """
      module leaf_adder #(parameter W = 4) (
        input  [W-1:0] a,
        input  [W-1:0] b,
        output [W-1:0] sum
      );
        assign sum = a + b;
      endmodule

      module mid_stage #(parameter WIDTH = 4) (
        input  [WIDTH-1:0] x,
        input  [WIDTH-1:0] y,
        output [WIDTH-1:0] z
      );
        leaf_adder #(.W(WIDTH)) u_leaf (
          .a(x),
          .b(y),
          .sum(z)
        );
      endmodule

      module top_dual_adder (
        input  [7:0] hi_a,
        input  [7:0] hi_b,
        input  [3:0] lo_a,
        input  [3:0] lo_b,
        output [7:0] hi_sum,
        output [3:0] lo_sum
      );
        mid_stage #(.WIDTH(8)) u_wide (
          .x(hi_a),
          .y(hi_b),
          .z(hi_sum)
        );
        mid_stage #(4) u_narrow (
          .x(lo_a),
          .y(lo_b),
          .z(lo_sum)
        );
      endmodule
    """.trimIndent()

    val res = RtlSynthesisEngine.synthesize(
      verilogSource = hierarchicalRtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.5,
      topModuleName = "top_dual_adder",
    )

    assertTrue("Expected Success but got $res", res is SynthesisResult.Success)
    val report = (res as SynthesisResult.Success).report
    assertEquals("top_dual_adder", report.moduleName)

    val addNodes = report.nodes.filter { it.gateType == GateType.ADD_CELL }
    assertTrue("Expected flattened ADD_CELL instances for both u_wide and u_narrow", addNodes.size >= 2)
    val widths = addNodes.map { it.bitWidth }.toSet()
    assertTrue("Expected 8-bit overridden ADD_CELL width in $widths", 8 in widths)
    assertTrue("Expected 4-bit overridden ADD_CELL width in $widths", 4 in widths)
    assertTrue(
      "Expected hierarchical prefix in flattened instances",
      addNodes.any { it.instanceName.contains("u_wide") } && addNodes.any { it.instanceName.contains("u_narrow") },
    )
  }

  @Test
  fun synthesisEngine_bitBlastModeDecomposesAddersIntoOneBitGateChains() {
    val rtl = """
      module alu_add8 (
        input  [7:0] a,
        input  [7:0] b,
        output [7:0] y
      );
        assign y = a + b;
      endmodule
    """.trimIndent()

    val macroRes = RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.NANGATE45_45NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 5.0,
      bitBlast = false,
    ) as SynthesisResult.Success

    val blastedRes = RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.NANGATE45_45NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 5.0,
      bitBlast = true,
    ) as SynthesisResult.Success

    assertTrue(macroRes.report.nodes.any { it.gateType == GateType.ADD_CELL })
    assertFalse("Bit-blasted view should lower ADD_CELL into 1-bit gates", blastedRes.report.nodes.any { it.gateType == GateType.ADD_CELL })
    assertTrue("Bit-blasted 8-bit adder should emit XOR2/AND2/OR2 chain", blastedRes.report.nodes.any { it.gateType == GateType.XOR2 })
    assertTrue("Bit-blasted cell count (${blastedRes.report.cellCount}) should exceed macro count (${macroRes.report.cellCount})", blastedRes.report.cellCount > macroRes.report.cellCount)
  }

  @Test
  fun simulator_capturesDisplayConsoleOutputAndPerCycleAssertionVerdicts() {
    val rtl = """
      module counter_assert (
        input clk,
        input rst,
        output reg [3:0] cnt
      );
        always @(posedge clk) begin
          if (rst) begin
            cnt <= 4'd0;
          end else begin
            cnt <= cnt + 4'd1;
            assert (cnt < 4'd2);
            ${'$'}display("cnt=%0d", cnt);
          end
        end
      endmodule
    """.trimIndent()

    val parsed = VerilogHdlParser.parse(rtl) as VerilogParseResult.Success
    val sim = RtlSimulator.simulate(design = parsed.design, cycleCount = 8)

    assertTrue("Expected \$display console output", sim.consoleLogs.isNotEmpty())
    assertTrue("Expected formatted cnt=... message", sim.consoleLogs.any { it.text.startsWith("cnt=") })
    assertEquals(1, sim.assertions.size)
    val assertion = sim.assertions.first()
    assertTrue("Expected assertion to pass initial cycles", assertion.passedCount > 0)
    assertTrue("Expected assertion to fail once cnt >= 2", assertion.failedCycles.isNotEmpty())
  }

  @Test
  fun projectHelpers_resolvesCrossTabIncludesGuardsCyclesAndMapsLines() {
    val defs = RtlFileEntry(
      id = "file_defs",
      name = "defs.svh",
      language = RtlLanguage.SYSTEM_VERILOG,
      content = "`define DW 8\n",
    )
    val sub = RtlFileEntry(
      id = "file_sub",
      name = "helper_inv.sv",
      language = RtlLanguage.SYSTEM_VERILOG,
      content = "`include \"defs.svh\"\nmodule helper_inv(input logic a, output logic y);\n  assign y = ~a;\nendmodule",
    )
    val top = RtlFileEntry(
      id = "file_rtl_top",
      name = "top_chip.sv",
      language = RtlLanguage.SYSTEM_VERILOG,
      content = "`include \"defs.svh\"\nmodule top_chip(input logic a, output logic y);\n  helper_inv u0(.a(a), .y(y));\nendmodule",
    )

    val bundle = RtlProjectHelpers.bundleProjectSources(listOf(top, sub, defs), top.content)
    assertTrue("Expected defs.svh in resolvedIncludes: ${bundle.resolvedIncludes}", "defs.svh" in bundle.resolvedIncludes)
    assertTrue(bundle.segments.isNotEmpty())

    // Find top_chip segment and verify combined line maps back to top_chip.sv local line 2
    val topSeg = bundle.segments.first { it.fileId == "file_rtl_top" }
    val mapped = RtlProjectHelpers.mapCombinedLineToFile(bundle, topSeg.startLine + 1)
    assertNotNull(mapped)
    assertEquals("file_rtl_top", mapped!!.first)
    assertEquals(2, mapped.second)

    // Verify circular `include guard does not infinite-loop
    val cycA = RtlFileEntry("a", "a.svh", RtlLanguage.SYSTEM_VERILOG, "`include \"b.svh\"\n`define FOO 1")
    val cycB = RtlFileEntry("b", "b.svh", RtlLanguage.SYSTEM_VERILOG, "`include \"a.svh\"\nmodule cyc(input a, output y); assign y = a; endmodule")
    val cyclicBundle = RtlProjectHelpers.bundleProjectSources(listOf(cycB, cycA), cycB.content)
    assertTrue(cyclicBundle.combinedSource.contains("already included"))
  }

  @Test
  fun exportShareHelper_buildsCompleteTapeoutZipArchive() {
    var flowEvent: RtlToGdsStageEvent? = null
    val rtl = "module zip_mod(input clk, input a, output reg q); always @(posedge clk) q <= ~a; endmodule"
    runBlocking {
      flowEvent = RtlToGdsFlowEngine.runFlow(
        rtlSource = rtl,
        designName = "zip_mod",
        library = TargetLibrary.SKY130_130NM,
        goal = OptimizationGoal.BALANCED,
        engineProfile = SynthesisEngineProfile.SILICON_FLOW,
        clockPeriodNs = 5.0,
        coreUtilizationPercent = 60,
        sdcText = "create_clock -name clk -period 5.0 [get_ports clk]\n",
        stepDelayMs = 0L,
        onProgress = {},
      )
    }
    val completed = flowEvent!!
    val report = (completed.synthesisResult as SynthesisResult.Success).report
    val files = listOf(RtlFileEntry("file_rtl_top", "zip_mod.v", RtlLanguage.VERILOG, rtl))

    val zipBytes = ExportShareHelper.buildTapeoutZipBytes(
      projectFiles = files,
      fallbackRtl = rtl,
      report = report,
      layout = completed.layoutSnapshot,
      sdcText = "create_clock -name clk -period 5.0 [get_ports clk]\n",
    )
    assertTrue(zipBytes.size > 256)

    val entries = mutableMapOf<String, Int>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        entries[entry.name] = zis.readBytes().size
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }

    assertTrue("Missing rtl entry in $entries", "rtl/zip_mod.v" in entries)
    assertTrue("Missing netlist entry in $entries", "netlist/zip_mod_synth.v" in entries)
    assertTrue("Missing sdc entry in $entries", "constraints/zip_mod.sdc" in entries)
    assertTrue("Missing rpt entry in $entries", "reports/zip_mod_sta_power.rpt" in entries)
    assertTrue("Missing tcl entry in $entries", "scripts/zip_mod_openroad.tcl" in entries)
    assertTrue("Missing gds entry in $entries", "gds/zip_mod.gds" in entries)
    assertTrue("Missing lyp entry in $entries", "gds/zip_mod.lyp" in entries)
    assertTrue("Missing png entry in $entries", "gds/zip_mod_klayout.png" in entries)
    assertTrue("Missing manifest.json in $entries", "manifest.json" in entries)
  }

  @Test
  fun pdkCatalogManager_parsesLefLibSummaryAndImportsLocalStreamWithSha256() {
    val sampleLef = """
      VERSION 5.8 ;
      SITE unithd ;
      LAYER met1 ;
      LAYER met2 ;
      MACRO sky130_fd_sc_hd__inv_1
      END sky130_fd_sc_hd__inv_1
      MACRO sky130_fd_sc_hd__nand2_1
      END sky130_fd_sc_hd__nand2_1
    """.trimIndent()
    val summary = PdkCatalogManager.parseLefLibSummary(sampleLef)
    assertEquals("LEF", summary.format)
    assertEquals(2, summary.macroOrCellCount)
    assertEquals(2, summary.layerCount)
    assertEquals(1, summary.siteCount)

    val context = ApplicationProvider.getApplicationContext<Context>()
    val imported = PdkCatalogManager.importLocalPdkStream(
      context = context,
      library = TargetLibrary.SKY130_130NM,
      fileName = "custom_sky130.lef",
      inputStream = ByteArrayInputStream(sampleLef.toByteArray(Charsets.UTF_8)),
    )
    assertTrue(imported.isSuccess)
    val status = imported.getOrThrow()
    assertTrue(status.isVerified)
    assertEquals(2, status.customCellCount)
    assertEquals("custom_sky130.lef", status.downloadedFileName)
    assertEquals(12, status.sha256Short.length)
  }
}
