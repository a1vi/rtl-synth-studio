package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlLanguage
import com.example.data.model.TargetLibrary

/**
 * Deterministic compact RTL fixtures for unit tests that assert specific signal/port names.
 */
object TestRtlFixtures {
  val counter8Bit = RtlPresetTemplate(
    id = "fixture_counter_8bit",
    name = "counter_8bit",
    category = "Test Fixture",
    language = RtlLanguage.VERILOG,
    description = "8-bit synchronous counter fixture for deterministic unit tests.",
    defaultClockNs = 2.0,
    defaultLibrary = TargetLibrary.ASAP7_7NM,
    defaultGoal = OptimizationGoal.BALANCED,
    verilogCode = """
module counter_8bit (
  input wire clk,
  input wire rst,
  input wire en,
  output reg [7:0] count,
  output wire tc
);
  assign tc = (count == 8'hFF) & en;

  always @(posedge clk) begin
    if (rst) begin
      count <= 8'd0;
    end else if (en) begin
      count <= count + 8'd1;
    end else begin
      count <= count;
    end
  end
endmodule
    """.trimIndent(),
  )

  val alu8BitSv = RtlPresetTemplate(
    id = "fixture_alu_8bit_sv",
    name = "alu_8bit_sv",
    category = "Test Fixture",
    language = RtlLanguage.SYSTEM_VERILOG,
    description = "Combinational ALU fixture for deterministic PDK scaling tests.",
    defaultClockNs = 1.5,
    defaultLibrary = TargetLibrary.NANGATE45_45NM,
    defaultGoal = OptimizationGoal.MIN_DELAY,
    verilogCode = """
module alu_8bit_sv (
  input logic [7:0] a,
  input logic [7:0] b,
  input logic [2:0] op,
  output logic [7:0] y,
  output logic zero
);
  assign zero = (y == 8'd0);

  always_comb begin
    case (op)
      3'b000: y = a + b;
      3'b001: y = a - b;
      3'b010: y = a & b;
      3'b011: y = a | b;
      3'b100: y = a ^ b;
      3'b101: y = (a < b) ? 8'd1 : 8'd0;
      default: y = a;
    endcase
  end
endmodule
    """.trimIndent(),
  )

  val counter4BitVhdl = RtlPresetTemplate(
    id = "fixture_counter_vhdl",
    name = "counter_4bit_vhdl",
    category = "Test Fixture",
    language = RtlLanguage.VHDL,
    description = "VHDL 4-bit counter fixture for parser verification.",
    defaultClockNs = 5.0,
    defaultLibrary = TargetLibrary.GF180MCU_180NM,
    defaultGoal = OptimizationGoal.BALANCED,
    verilogCode = """
library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity counter_4bit_vhdl is
  port (
    clk   : in  std_logic;
    rst   : in  std_logic;
    en    : in  std_logic;
    count : out std_logic_vector(3 downto 0)
  );
end entity counter_4bit_vhdl;

architecture rtl of counter_4bit_vhdl is
  signal q_reg : std_logic_vector(3 downto 0);
begin
  count <= q_reg;

  process(clk)
  begin
    if rising_edge(clk) then
      if rst = '1' then
        q_reg <= "0000";
      elsif en = '1' then
        q_reg <= q_reg + "0001";
      end if;
    end if;
  end process;
end architecture rtl;
    """.trimIndent(),
  )
}
