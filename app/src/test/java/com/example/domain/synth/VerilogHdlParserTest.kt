package com.example.domain.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerilogHdlParserTest {

  @Test
  fun parseAnsiModule_extractsPortsAndInternalLogicSignals() {
    val code = """
      module alu_core #(
        parameter WIDTH = 8
      ) (
        input wire clk,
        input wire signed [WIDTH-1:0] a,
        input wire signed [WIDTH-1:0] b,
        input wire [1:0] op,
        output reg [WIDTH-1:0] result,
        output wire zero_flag
      );
        wire [WIDTH-1:0] sum_w;
        reg carry_r;

        assign sum_w = a + b;
        assign zero_flag = (result == 8'd0);

        always @(posedge clk) begin
          case (op)
            2'b00: result <= sum_w;
            default: result <= a ^ b;
          endcase
        end
      endmodule
    """.trimIndent()

    val res = VerilogHdlParser.parse(code)
    assertTrue("Expected parse success, got $res", res is VerilogParseResult.Success)
    val design = (res as VerilogParseResult.Success).design
    val mod = design.topModule

    assertEquals("alu_core", mod.name)
    assertEquals(1, mod.parameters.size)
    assertEquals(8, mod.parameters.first().value)

    assertEquals(6, mod.ports.size)
    assertEquals(4, mod.inputPorts.size)
    assertEquals(2, mod.outputPorts.size)

    val portA = mod.ports.first { it.name == "a" }
    assertEquals(8, portA.width)
    assertTrue(portA.isSigned)
    assertEquals(PortDirection.INPUT, portA.direction)

    val portRes = mod.ports.first { it.name == "result" }
    assertTrue(portRes.isReg)
    assertEquals(8, portRes.width)

    val internal = mod.internalSignals
    assertEquals(2, internal.size)
    assertTrue(internal.any { it.name == "sum_w" && it.kind == LogicSignalKind.WIRE && it.width == 8 })
    assertTrue(internal.any { it.name == "carry_r" && it.kind == LogicSignalKind.REG && it.width == 1 })
  }

  @Test
  fun parseVerilog1995AndMultiModuleHierarchy_identifiesTopAndSubmodules() {
    val code = """
      module half_adder(a, b, sum, cout);
        input a, b;
        output sum, cout;
        assign sum = a ^ b;
        assign cout = a & b;
      endmodule

      module two_bit_top(
        input wire [1:0] x,
        input wire [1:0] y,
        output wire [1:0] s,
        output wire c1
      );
        wire c0;
        half_adder u_ha0 (.a(x[0]), .b(y[0]), .sum(s[0]), .cout(c0));
        half_adder u_ha1 (.a(x[1]), .b(y[1]), .sum(s[1]), .cout(c1));
      endmodule
    """.trimIndent()

    val res = VerilogHdlParser.parse(code)
    assertTrue("Expected parse success, got $res", res is VerilogParseResult.Success)
    val design = (res as VerilogParseResult.Success).design

    assertEquals(2, design.modules.size)
    assertEquals("two_bit_top", design.topModule.name)
    assertEquals(2, design.topModule.instances.size)
    assertEquals("half_adder", design.topModule.instances[0].moduleType)
    assertEquals("u_ha0", design.topModule.instances[0].instanceName)
    assertEquals(1, design.topModule.internalSignals.size)
    assertEquals("c0", design.topModule.internalSignals[0].name)

    val subMod = design.modules.first { it.name == "half_adder" }
    assertEquals(2, subMod.inputPorts.size)
    assertEquals(2, subMod.outputPorts.size)
  }

  @Test
  fun parseMalformedVerilog_returnsExactLineError() {
    val bad = """
      module broken (
        input wire a,
        output wire y
      );
        assign y = a & ;
      endmodule
    """.trimIndent()

    val res = VerilogHdlParser.parse(bad)
    assertTrue(res is VerilogParseResult.Error)
    val err = res as VerilogParseResult.Error
    assertEquals(5, err.line)
  }
}
