package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary

data class RtlPresetTemplate(
  val id: String,
  val name: String,
  val category: String,
  val description: String,
  val defaultClockNs: Double,
  val defaultLibrary: TargetLibrary,
  val defaultGoal: OptimizationGoal,
  val verilogCode: String,
)

object PresetRtlDesigns {
  val templates: List<RtlPresetTemplate> = listOf(
    RtlPresetTemplate(
      id = "preset_counter_8bit",
      name = "counter_8bit",
      category = "Sequential",
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
    ),
    RtlPresetTemplate(
      id = "preset_alu_8bit",
      name = "alu_8bit",
      category = "Datapath",
      description = "8-bit combinational ALU supporting ADD, SUB, AND, OR, XOR, and comparison.",
      defaultClockNs = 1.5,
      defaultLibrary = TargetLibrary.ASAP7_7NM,
      defaultGoal = OptimizationGoal.MIN_DELAY,
      verilogCode = """
module alu_8bit (
  input wire [7:0] a,
  input wire [7:0] b,
  input wire [2:0] op,
  output reg [7:0] y,
  output wire zero
);
  assign zero = (y == 8'd0);

  always @(*) begin
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
    ),
    RtlPresetTemplate(
      id = "preset_traffic_fsm",
      name = "traffic_fsm",
      category = "Control FSM",
      description = "3-state Moore traffic controller FSM with pedestrian request override.",
      defaultClockNs = 4.0,
      defaultLibrary = TargetLibrary.SKY130_130NM,
      defaultGoal = OptimizationGoal.MIN_AREA,
      verilogCode = """
module traffic_fsm (
  input wire clk,
  input wire rst,
  input wire ped_req,
  output reg [1:0] state,
  output wire red,
  output wire yellow,
  output wire green
);
  assign red = (state == 2'd0);
  assign green = (state == 2'd1);
  assign yellow = (state == 2'd2);

  always @(posedge clk) begin
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
      id = "preset_adder_4bit",
      name = "adder_4bit",
      category = "Combinational",
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
