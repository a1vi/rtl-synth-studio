package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdcConstraintsAndStaTest {

  @Test
  fun sdcParser_parsesClocksIoDelaysUncertaintyAndExceptions() {
    val sdcText = """
      # Multi-clock + I/O constrained SDC
      create_clock -name sys_clk -period 4.0 [get_ports clk]
      create_clock -name aux_clk -period 8.0 [get_ports aux_clk]
      set_clock_uncertainty 0.25 [get_clocks sys_clk]
      set_input_delay -clock sys_clk 0.80 [get_ports data_*]
      set_output_delay -clock sys_clk 0.65 [get_ports y]
      set_false_path -from [get_ports rst] -to [get_ports dbg_*]
      set_max_delay 3.50 -from [get_ports data_in] -to [get_ports y]
    """.trimIndent()

    val parsed = SdcParser.parse(sdcText, fallbackClockNs = 2.0)
    assertEquals(2, parsed.clocks.size)
    assertEquals("sys_clk", parsed.clocks[0].name)
    assertEquals(4.0, parsed.clocks[0].periodNs, 1e-4)
    assertEquals(0.25, parsed.clockUncertaintyNs, 1e-4)
    assertEquals(0.80, parsed.inputDelayForPort("data_in"), 1e-4)
    assertEquals(0.0, parsed.inputDelayForPort("other"), 1e-4)
    assertEquals(0.65, parsed.outputDelayForPort("y"), 1e-4)
    assertTrue(parsed.isFalsePathEndpoint("dbg_out", listOf("rst")))
    assertEquals(3.50, parsed.maxDelayOverrideFor("y", listOf("data_in")) ?: 0.0, 1e-4)
    assertTrue(parsed.diagnostics.isEmpty())
  }

  @Test
  fun synthesisEngine_appliesSdcIoDelaysUncertaintyAndFalsePath() {
    val rtl = """
      module sdc_pipe (
        input wire clk,
        input wire a,
        input wire b,
        output wire y,
        output wire dbg_out
      );
        wire ab;
        assign ab = a & b;
        assign y = ~ab;
        assign dbg_out = a ^ b;
      endmodule
    """.trimIndent()

    val baseSdc = """
      create_clock -name clk -period 2.00 [get_ports clk]
    """.trimIndent()

    val constrainedSdc = """
      create_clock -name clk -period 2.00 [get_ports clk]
      set_clock_uncertainty 0.20 [get_clocks clk]
      set_input_delay -clock clk 0.60 [get_ports a]
      set_output_delay -clock clk 0.50 [get_ports y]
      set_false_path -to [get_ports dbg_out]
    """.trimIndent()

    val baseReport = (RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
      sdcText = baseSdc,
    ) as SynthesisResult.Success).report

    val constrainedReport = (RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
      sdcText = constrainedSdc,
    ) as SynthesisResult.Success).report

    assertTrue(
      "Input delay + output delay + clock uncertainty must tighten WNS (${constrainedReport.wnsNs} vs ${baseReport.wnsNs})",
      constrainedReport.wnsNs < baseReport.wnsNs - 0.5,
    )
    assertTrue(constrainedReport.falsePathCount >= 1)
    assertTrue(constrainedReport.nodes.any { it.isFalsePath && it.outputNet == "dbg_out" })
    assertEquals(0.20, constrainedReport.clockUncertaintyNs, 1e-4)
    assertTrue(constrainedReport.appliedSdcConstraints >= 4)
  }

  @Test
  fun sdcParser_updateClockPeriodUpdatesFirstClockInPlace() {
    val initial = """
      create_clock -name clk -period 2.50 [get_ports clk]
      set_input_delay -clock clk 0.30 [get_ports *]
    """.trimIndent()
    val updated = SdcParser.updateClockPeriodInSdc(initial, 4.2)
    assertTrue(updated.contains("-period 4.20"))
    val reparsed = SdcParser.parse(updated, 2.0)
    assertEquals(4.2, reparsed.primaryClockPeriodNs ?: 0.0, 1e-4)
  }

  @Test
  fun sdcParser_parsesAndSerializesOutputLoads() {
    val sdc = """
      create_clock -name clk -period 2.50 [get_ports clk]
      set_input_delay -clock clk 0.40 [get_ports data_*]
      set_output_delay -clock clk 0.30 [get_ports *]
      set_load -pin_load 1.25 [get_ports y]
      set_load -wire_load 0.40 [get_ports dbg_*]
    """.trimIndent()

    val parsed = SdcParser.parse(sdc, fallbackClockNs = 2.0)
    assertTrue("No diagnostics expected: ${parsed.diagnostics}", parsed.diagnostics.isEmpty())
    assertEquals(2, parsed.outputLoads.size)
    assertEquals(1.25, parsed.outputLoadForPort("y"), 1e-4)
    assertEquals(0.40, parsed.outputLoadForPort("dbg_out"), 1e-4)
    assertEquals(0.0, parsed.outputLoadForPort("unconstrained"), 1e-4)

    val serialized = SdcParser.serialize(
      clocks = parsed.clocks,
      ioDelays = parsed.ioDelays,
      outputLoads = parsed.outputLoads,
      clockUncertaintyNs = parsed.clockUncertaintyNs,
      exceptions = parsed.exceptions,
      moduleName = "top",
    )
    val roundTripped = SdcParser.parse(serialized, fallbackClockNs = 2.0)
    assertEquals(parsed.clocks.size, roundTripped.clocks.size)
    assertEquals(1.25, roundTripped.outputLoadForPort("y"), 1e-4)
    assertEquals(0.40, roundTripped.outputLoadForPort("dbg_out"), 1e-4)
    assertEquals(0.40, roundTripped.inputDelayForPort("data_in"), 1e-4)
  }

  @Test
  fun synthesisEngine_outputLoadIncreasesArrivalAndTightensWns() {
    val rtl = """
      module load_test (
        input wire a,
        input wire b,
        output wire y
      );
        assign y = ~(a & b);
      endmodule
    """.trimIndent()

    val noLoadSdc = "create_clock -name clk -period 2.00 [get_ports clk]"
    val heavyLoadSdc = """
      create_clock -name clk -period 2.00 [get_ports clk]
      set_load -pin_load 2.50 [get_ports y]
    """.trimIndent()

    val baseReport = (RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
      sdcText = noLoadSdc,
    ) as SynthesisResult.Success).report

    val loadedReport = (RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
      sdcText = heavyLoadSdc,
    ) as SynthesisResult.Success).report

    val expectedExtraDelay = SdcParser.outputLoadDelayNs(2.50, TargetLibrary.SKY130_130NM)
    assertTrue("Expected non-zero output load delay", expectedExtraDelay > 1.0)
    assertEquals(2.50, loadedReport.maxOutputLoadPf, 1e-4)
    assertEquals(
      baseReport.criticalPathDelayNs + expectedExtraDelay,
      loadedReport.criticalPathDelayNs,
      1e-3,
    )
    assertTrue("Heavy output load must reduce WNS", loadedReport.wnsNs < baseReport.wnsNs - 1.0)

    val staSummary = StaticTimingAnalyzer.analyze(loadedReport, layout = null)
    val critStage = staSummary.paths.first().stages.last { it.gateType == GateType.OUTPUT_PORT }
    assertEquals(expectedExtraDelay, critStage.netDelayNs, 1e-3)
    assertEquals(expectedExtraDelay, critStage.incrementalDelayNs, 1e-3)
  }
}
