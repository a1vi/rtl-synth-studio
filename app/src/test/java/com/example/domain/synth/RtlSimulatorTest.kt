package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtlSimulatorTest {

  @Test
  fun simulateSequentialCounter_resetsThenIncrementsEachCycle() {
    val code = """
      module up_cnt (
        input wire clk,
        input wire rst,
        output reg [3:0] count
      );
        always @(posedge clk) begin
          if (rst) begin
            count <= 4'd0;
          end else begin
            count <= count + 4'd1;
          end
        end
      endmodule
    """.trimIndent()

    val design = (VerilogHdlParser.parse(code) as VerilogParseResult.Success).design
    val sim = RtlSimulator.simulate(
      design = design,
      cycleCount = 8,
      patternOverrides = mapOf("rst" to StimulusPattern.PULSE_HIGH),
    )

    assertEquals("up_cnt", sim.moduleName)
    assertEquals(8, sim.cycleCount)
    assertEquals("clk", sim.clockPort)

    val rstTrace = sim.traces.first { it.name == "rst" }
    val countTrace = sim.traces.first { it.name == "count" }
    assertEquals(listOf(1L, 1L, 0L, 0L, 0L, 0L, 0L, 0L), rstTrace.values)
    // Cycle 0: init 0, rst=1 -> edge commits 0
    // Cycle 1: rst=1 -> edge commits 0
    // Cycle 2: rst=0, count=0 -> edge commits 1 -> visible at Cycle 3
    assertEquals(listOf(0L, 0L, 0L, 1L, 2L, 3L, 4L, 5L), countTrace.values)

    assertEquals("0x5", countTrace.formatValue(7, WaveformRadix.HEX))
    assertEquals("5", countTrace.formatValue(7, WaveformRadix.DEC))
    assertEquals("0101", countTrace.formatValue(7, WaveformRadix.BIN))
  }

  @Test
  fun simulateCombinational_respectsCustomCycleOverrides() {
    val code = """
      module comb_xor (
        input wire [3:0] a,
        input wire [3:0] b,
        output wire [3:0] y
      );
        assign y = a ^ b;
      endmodule
    """.trimIndent()

    val design = (VerilogHdlParser.parse(code) as VerilogParseResult.Success).design
    val sim = RtlSimulator.simulate(
      design = design,
      cycleCount = 4,
      patternOverrides = mapOf(
        "a" to StimulusPattern.LOW,
        "b" to StimulusPattern.LOW,
      ),
      customValueOverrides = mapOf(
        ("a" to 2) to 0xA,
        ("b" to 2) to 0x3,
      ),
    )

    val yTrace = sim.traces.first { it.name == "y" }
    assertEquals(0L, yTrace.values[0])
    assertEquals(0L, yTrace.values[1])
    assertEquals(0x9L, yTrace.values[2])
    assertEquals(0L, yTrace.values[3])
  }

  @Test
  fun simulateManualNetlist_propagatesCombAndSeqGates() {
    val blank = RtlSynthesisEngine.createBlankManualReport(
      moduleName = "man_xor",
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    )
    val xorNode = GateNode(
      id = "g1",
      cellName = "XOR2",
      gateType = GateType.XOR2,
      instanceName = "u_xor",
      inputs = listOf("in_a", "in_b"),
      outputNet = "w_x",
      bitWidth = 1,
      delayNs = 0.0,
      areaUm2 = 0.0,
      powerUw = 0.0,
    )
    val updated = RtlSynthesisEngine.recomputeReportFromNodes(
      moduleName = blank.moduleName,
      ports = blank.ports,
      rawNodes = blank.nodes.map { if (it.id == "out_out_y") it.copy(inputs = listOf("w_x")) else it } + xorNode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    )

    val sim = RtlSimulator.simulateManualNetlist(
      report = updated,
      cycleCount = 4,
      patternOverrides = mapOf(
        "in_a" to StimulusPattern.HIGH,
        "in_b" to StimulusPattern.LOW,
      ),
    )
    val yTrace = sim.traces.first { it.name == "out_y" }
    assertTrue(yTrace.values.all { it == 1L })
  }
}
