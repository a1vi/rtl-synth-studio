package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtlSynthesisEngineTest {

  @Test
  fun synthesizeCounter_producesSequentialAndCombinationalCells() {
    val preset = PresetRtlDesigns.templates.first { it.name == "counter_8bit" }
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = preset.verilogCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    )
    assertTrue("Expected synthesis success, got $result", result is SynthesisResult.Success)
    val report = (result as SynthesisResult.Success).report
    assertEquals("counter_8bit", report.moduleName)
    assertTrue("Expected at least 1 sequential flip-flop", report.seqCellCount >= 1)
    assertTrue("Expected combinational cells", report.combCellCount >= 1)
    assertTrue("Expected positive WNS at 2.0ns on 7nm", report.wnsNs > 0.0)
    assertTrue("Expected structural Verilog module output", report.netlistVerilog.contains("module counter_8bit_synth"))
  }

  @Test
  fun synthesizeAlu_sky130_vs_asap7_scalesDelayAndArea() {
    val preset = PresetRtlDesigns.templates.first { it.name == "alu_8bit_sv" }
    val asap7 = (RtlSynthesisEngine.synthesize(
      preset.verilogCode, TargetLibrary.ASAP7_7NM, OptimizationGoal.MIN_DELAY, 2.0
    ) as SynthesisResult.Success).report
    val sky130 = (RtlSynthesisEngine.synthesize(
      preset.verilogCode, TargetLibrary.SKY130_130NM, OptimizationGoal.MIN_DELAY, 2.0
    ) as SynthesisResult.Success).report
    val gf180 = (RtlSynthesisEngine.synthesize(
      preset.verilogCode, TargetLibrary.GF180MCU_180NM, OptimizationGoal.MIN_DELAY, 2.0
    ) as SynthesisResult.Success).report
    val nangate45 = (RtlSynthesisEngine.synthesize(
      preset.verilogCode, TargetLibrary.NANGATE45_45NM, OptimizationGoal.MIN_DELAY, 2.0
    ) as SynthesisResult.Success).report

    assertTrue("7nm should have smaller area than 45nm", asap7.totalAreaUm2 < nangate45.totalAreaUm2)
    assertTrue("45nm should have smaller area than 130nm", nangate45.totalAreaUm2 < sky130.totalAreaUm2)
    assertTrue("130nm should have smaller area than 180nm", sky130.totalAreaUm2 < gf180.totalAreaUm2)
    assertTrue("7nm should be faster than 130nm", asap7.criticalPathDelayNs < sky130.criticalPathDelayNs)
    assertTrue("130nm should be faster than 180nm", sky130.criticalPathDelayNs < gf180.criticalPathDelayNs)
  }

  @Test
  fun synthesizeInvalidVerilog_returnsLineError() {
    val badCode = "wire a;\nassign a = 1'b0;"
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = badCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 5.0,
    )
    assertTrue(result is SynthesisResult.Error)
  }

  @Test
  fun manualSchematic_recomputesMappingEdgesAndVerilog() {
    val blank = RtlSynthesisEngine.createBlankManualReport(
      moduleName = "hand_built",
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    )
    assertEquals(3, blank.nodes.size) // in_a, in_b, out_y

    val andGate = GateNode(
      id = "man_and",
      cellName = "AND2",
      gateType = GateType.AND2,
      instanceName = "u_and_1",
      inputs = listOf("in_a", "in_b"),
      outputNet = "w_and",
      bitWidth = 1,
      delayNs = 0.0,
      areaUm2 = 0.0,
      powerUw = 0.0,
    )
    val dffGate = GateNode(
      id = "man_dff",
      cellName = "DFF",
      gateType = GateType.DFF,
      instanceName = "u_dff_1",
      inputs = listOf("w_and", "clk"),
      outputNet = "w_q",
      bitWidth = 1,
      delayNs = 0.0,
      areaUm2 = 0.0,
      powerUw = 0.0,
    )
    val wiredNodes = blank.nodes.map {
      if (it.id == "out_out_y") it.copy(inputs = listOf("w_q")) else it
    } + listOf(andGate, dffGate)

    val updated = RtlSynthesisEngine.recomputeReportFromNodes(
      moduleName = blank.moduleName,
      ports = blank.ports,
      rawNodes = wiredNodes,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    )

    assertEquals(1, updated.combCellCount)
    assertEquals(1, updated.seqCellCount)
    assertTrue(updated.edges.any { it.fromNodeId == "man_and" && it.toNodeId == "man_dff" })
    assertTrue(updated.edges.any { it.fromNodeId == "man_dff" && it.toNodeId == "out_out_y" })
    assertTrue(updated.netlistVerilog.contains("AND2x1_ASAP7 u_and_1"))
    assertTrue(updated.netlistVerilog.contains("DFFx1_ASAP7 u_dff_1"))
  }
}
