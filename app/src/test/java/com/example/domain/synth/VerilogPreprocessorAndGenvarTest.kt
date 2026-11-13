package com.example.domain.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerilogPreprocessorAndGenvarTest {

  @Test
  fun preprocess_handlesDefinesNestedIfdefElsifUndefAndPreservesLines() {
    val src = """
      `timescale 1ns/1ps
      `define WIDTH 8
      `define USE_ADD
      module pp_test(
        input wire [`WIDTH-1:0] a,
        input wire [`WIDTH-1:0] b,
      `ifdef USE_ADD
        `ifdef EXTRA_PORT
        output wire [`WIDTH-1:0] extra,
        `else
        output wire [`WIDTH-1:0] y
        `endif
      `elsif USE_SUB
        output wire [`WIDTH-1:0] y
      `else
        output wire [`WIDTH-1:0] y
      `endif
      );
      `undef USE_ADD
      `ifdef USE_ADD
        assign y = a - b;
      `else
        assign y = a + b;
      `endif
      endmodule
    """.trimIndent()

    val pp = VerilogPreprocessor.preprocess(src)
    assertEquals("Line count must stay 1:1 with input", src.lines().size, pp.processedSource.lines().size)
    assertTrue(pp.definedMacros.containsKey("WIDTH"))
    assertFalse("USE_ADD should be undefined after `undef", pp.definedMacros.containsKey("USE_ADD"))

    val parsed = VerilogHdlParser.parse(src)
    assertTrue("Expected parse success, got $parsed", parsed is VerilogParseResult.Success)
    val mod = (parsed as VerilogParseResult.Success).design.topModule
    assertEquals("pp_test", mod.name)
    assertEquals(8, mod.ports.first { it.name == "a" }.width)
    assertEquals(8, mod.ports.first { it.name == "y" }.width)
    assertEquals(1, mod.assignments.size)
    val expr = mod.assignments.first().expr as VerilogExpr.Binary
    assertEquals("+", expr.op)
  }

  @Test
  fun parse_unrollsGenvarGenerateForAndGenerateIfAndAlwaysFor() {
    val src = """
      module gen_bus_ops #(
        parameter N = 4,
        parameter INVERT_MSB = 1
      ) (
        input wire clk,
        input wire [N-1:0] d,
        output wire [N-1:0] q_comb,
        output reg [N-1:0] q_reg
      );
        genvar i;
        generate
          for (i = 0; i < N - 1; i = i + 1) begin : g_lane
            assign q_comb[i] = ~d[i];
          end
          if (INVERT_MSB) begin : g_msb
            assign q_comb[N-1] = ~d[N-1];
          end else begin : g_msb_buf
            assign q_comb[N-1] = d[N-1];
          end
        endgenerate

        integer k;
        always @(posedge clk) begin
          for (k = 0; k < N; k = k + 1) begin
            q_reg[k] <= d[k];
          end
        end
      endmodule
    """.trimIndent()

    val parsed = VerilogHdlParser.parse(src)
    assertTrue("Expected parse success, got $parsed", parsed is VerilogParseResult.Success)
    val mod = (parsed as VerilogParseResult.Success).design.topModule

    // 3 generated assign lanes (i=0..2) + 1 generate-if MSB lane = 4 bit slice assigns
    assertEquals(4, mod.assignments.size)
    assertEquals(listOf(0, 1, 2, 3), mod.assignments.mapNotNull { it.targetMsb }.sorted())

    // Sequential always block unrolled into 4 bit slice assignments
    val seqBlock = mod.alwaysBlocks.single { it.isSequential }
    assertEquals(4, seqBlock.statements.size)
    val sliceTargets = seqBlock.statements.filterIsInstance<VerilogStmt.Assign>().mapNotNull { it.targetMsb }
    assertEquals(listOf(0, 1, 2, 3), sliceTargets.sorted())
  }
}
