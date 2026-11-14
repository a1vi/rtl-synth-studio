package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlLanguage
import com.example.data.model.TargetLibrary

data class RtlPresetTemplate(
  val id: String,
  val name: String,
  val category: String,
  val language: RtlLanguage = RtlLanguage.VERILOG,
  val description: String,
  val defaultClockNs: Double,
  val defaultLibrary: TargetLibrary,
  val defaultGoal: OptimizationGoal,
  val verilogCode: String,
  val testbenchCode: String = "",
)

object PresetRtlDesigns {
  val templates: List<RtlPresetTemplate> = listOf(
    RtlPresetTemplate(
      id = "preset_counter_8bit",
      name = "counter_8bit",
      category = "Sequential",
      language = RtlLanguage.VERILOG,
      description = "8-bit synchronous up-counter with active-high reset, enable, and terminal count flag.",
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
      testbenchCode = """
// Auto-generated RTL testbench for counter_8bit
`timescale 1ns/1ps
module tb_counter_8bit;
  reg clk = 0;
  reg rst = 1;
  reg en = 1;
  wire [7:0] count;
  wire tc;

  counter_8bit dut (
    .clk(clk), .rst(rst), .en(en), .count(count), .tc(tc)
  );

  always #1 clk = ~clk;

  initial begin
    rst = 1; en = 0;
    #2 rst = 0; en = 1;
    #30 ${'$'}finish;
  end
endmodule
      """.trimIndent(),
    ),
    RtlPresetTemplate(
      id = "preset_alu_8bit_sv",
      name = "alu_8bit_sv",
      category = "Datapath · SV",
      language = RtlLanguage.SYSTEM_VERILOG,
      description = "SystemVerilog 8-bit combinational ALU using logic types and always_comb.",
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
      testbenchCode = """
`timescale 1ns/1ps
module tb_alu_8bit_sv;
  logic [7:0] a = 8'h12;
  logic [7:0] b = 8'h05;
  logic [2:0] op = 3'b000;
  logic [7:0] y;
  logic zero;

  alu_8bit_sv dut (.*);

  initial begin
    op = 3'b000;
    #2 op = 3'b001;
    #2 op = 3'b100;
  end
endmodule
      """.trimIndent(),
    ),
    RtlPresetTemplate(
      id = "preset_traffic_fsm_sv",
      name = "traffic_fsm",
      category = "Control FSM · SV",
      language = RtlLanguage.SYSTEM_VERILOG,
      description = "SystemVerilog 3-state Moore traffic controller FSM using always_ff.",
      defaultClockNs = 4.0,
      defaultLibrary = TargetLibrary.SKY130_130NM,
      defaultGoal = OptimizationGoal.MIN_AREA,
      verilogCode = """
module traffic_fsm (
  input logic clk,
  input logic rst,
  input logic ped_req,
  output logic [1:0] state,
  output logic red,
  output logic yellow,
  output logic green
);
  assign red = (state == 2'd0);
  assign green = (state == 2'd1);
  assign yellow = (state == 2'd2);

  always_ff @(posedge clk) begin
    if (rst) begin
      state <= 2'd0;
    end else begin
      case (state)
        2'd0: state <= ped_req ? 2'd0 : 2'd1;
        2'd1: state <= ped_req ? 2'd2 : 2'd1;
        2'd2: state <= 2'd0;
        default: state <= 2'd0;
      endcase
    end
  end
endmodule
      """.trimIndent(),
    ),
    RtlPresetTemplate(
      id = "preset_counter_vhdl",
      name = "counter_4bit_vhdl",
      category = "Sequential · VHDL",
      language = RtlLanguage.VHDL,
      description = "IEEE-1076 VHDL 4-bit synchronous counter with rising_edge clocking.",
      defaultClockNs = 5.0,
      defaultLibrary = TargetLibrary.GF180MCU_180NM,
      defaultGoal = OptimizationGoal.BALANCED,
      verilogCode = """
-- VHDL-2008 4-bit Synchronous Counter
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
    ),
    RtlPresetTemplate(
      id = "preset_adder_4bit",
      name = "adder_4bit",
      category = "Combinational",
      language = RtlLanguage.VERILOG,
      description = "4-bit ripple-carry style combinational adder with carry-in and carry-out.",
      defaultClockNs = 1.2,
      defaultLibrary = TargetLibrary.ARTIX7_FPGA,
      defaultGoal = OptimizationGoal.BALANCED,
      verilogCode = """
module adder_4bit (
  input wire [3:0] a,
  input wire [3:0] b,
  input wire cin,
  output wire [3:0] sum,
  output wire cout
);
  wire [3:0] p;
  wire [3:0] g;
  assign p = a ^ b;
  assign g = a & b;
  assign sum = p ^ cin;
  assign cout = g | (p & cin);
endmodule
      """.trimIndent(),
    ),
    RtlPresetTemplate(
      id = "preset_mac_pipelined",
      name = "mac_pipelined",
      category = "DSP / AI",
      language = RtlLanguage.VERILOG,
      description = "Registered 8-bit multiply-accumulate datapath for edge DSP inference.",
      defaultClockNs = 3.0,
      defaultLibrary = TargetLibrary.ARTIX7_FPGA,
      defaultGoal = OptimizationGoal.MIN_DELAY,
      verilogCode = """
module mac_pipelined (
  input wire clk,
  input wire rst,
  input wire valid_in,
  input wire [7:0] x,
  input wire [7:0] w,
  output reg [15:0] acc,
  output reg valid_out
);
  wire [15:0] prod;
  assign prod = x * w;

  always @(posedge clk) begin
    if (rst) begin
      acc <= 16'd0;
      valid_out <= 1'b0;
    end else begin
      acc <= valid_in ? (acc + prod) : acc;
      valid_out <= valid_in;
    end
  end
endmodule
      """.trimIndent(),
    ),
  )
}
