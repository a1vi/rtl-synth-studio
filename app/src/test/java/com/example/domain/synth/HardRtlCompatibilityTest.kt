package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardRtlCompatibilityTest {

  @Test
  fun complexSystemVerilogCore_parsesSynthesizesAndSimulates() {
    val hardSv = """
      `timescale 1ns/1ps
      `pragma resetall
      `begin_keywords "1800-2017"
      `define ADD_MASK 32'sh0000_FFFF
      `define CLAMP_U8(val, hi) \
        (((val) > (hi)) ? (hi) : (val))

      // synopsys translate_off
      `define SIM_ONLY_BANNER 1
      // synopsys translate_on

      package core_pkg;
        localparam int OPC_ALU = 7'h33;
        localparam int OPC_IMM = 7'h13;
        typedef enum logic [1:0] {
          ST_IDLE = 2'd0,
          ST_EXEC = 2'd1,
          ST_WB   = 2'd2
        } fsm_e;
        typedef struct packed {
          logic valid;
          logic [7:0] tag;
        } meta_t;
        function automatic int mix_bits(input int x, input int y);
          return (x ^ (y >>> 1));
        endfunction
      endpackage : core_pkg

      interface bus_if;
        logic valid;
        logic ready;
        modport master(output valid, input ready);
      endinterface

      module mac_lane #(
        parameter int W = 16
      ) (
        input logic clk,
        input logic [W-1:0] a,
        input logic [W-1:0] b,
        output logic [W-1:0] y
      );
        logic [W-1:0] prod_r;
        always_ff @(posedge clk) begin
          prod_r <= (a * b) ^ W'(a);
        end
        assign y = prod_r;
      endmodule

      module hard_riscv_ctrl import core_pkg::*; #(
        parameter int XLEN = 32,
        parameter int REGS = 8,
        localparam int IDX_W = ${'$'}clog2(REGS)
      ) (
        input  logic clk,
        input  logic rst_n,
        input  logic irq_i,
        input  logic [XLEN-1:0] instr_i,
        input  logic [15:0] op_a,
        input  logic [15:0] op_b,
        output logic [XLEN-1:0] res_o,
        output logic carry_o,
        output logic evt_o
      );
        (* fsm_encoding = "one_hot", keep = "true" *)
        core_pkg::fsm_e state_q, state_d;
        logic [XLEN-1:0] rf [0:REGS-1];
        logic [15:0] sum_16;
        logic [15:0] lane_out [0:3];
        logic [3:0] lane_lsb;
        wire [XLEN-1:0] imm_ext = {{20{instr_i[31]}}, instr_i[31:20]};
        wire n0, busy_w;

        // Built-in Verilog gate primitives
        nand #1 u_nand_irq (n0, irq_i, busy_w);
        xor  u_xor_evt (evt_o, n0, carry_o);

        // Initial memory setup (ignored gracefully in synth)
        integer init_i;
        initial begin : init_rf
          for (init_i = 0; init_i < REGS; ++init_i) begin
            rf[init_i] = '0;
          end
        end : init_rf

        // Explicit generate-for of parameterized submodules
        genvar g;
        generate
          for (g = 0; g < 4; g = g + 1) begin : g_lanes
            wire [15:0] lane_w;
            mac_lane #(.W(16)) u_lane (
              .clk,
              .a(op_a + g),
              .b(op_b),
              .y(lane_w)
            );
            assign lane_lsb[g] = lane_w[0];
          end
        endgenerate

        always_comb begin : comb_alu
          logic [XLEN-1:0] tmp;
          integer acc_tmp;
          state_d = state_q;
          sum_16 = 16'd0;
          carry_o = 1'b0;
          tmp = '0;
          acc_tmp = 0;

          if (instr_i[6:0] inside {OPC_ALU, OPC_IMM, [7'h60:7'h6f]}) begin
            {carry_o, sum_16} = op_a + op_b + {{15{1'b0}}, lane_lsb[0]};
            tmp = {>>{imm_ext[15:0], sum_16}} & `ADD_MASK;
            acc_tmp += `CLAMP_U8(op_a[7:0], 8'hFE);
            acc_tmp++;
          end

          unique casez (state_q)
            ST_IDLE: begin
              if (irq_i) state_d = ST_EXEC;
            end
            ST_EXEC: state_d = ST_WB;
            default: state_d = ST_IDLE;
          endcase

          res_o = (state_q == ST_WB) ? rf[instr_i[2:0]] : (tmp ^ core_pkg::mix_bits(acc_tmp, imm_ext[7:0]));
        end : comb_alu

        assign busy_w = (state_q !== ST_IDLE);

        integer wi;
        always_ff @(posedge clk or negedge rst_n) begin : seq_regs
          if (!rst_n) begin
            state_q <= ST_IDLE;
            for (wi = 0; wi < REGS; wi += 1) begin
              rf[wi] <= '0;
            end
          end else begin
            state_q <= state_d;
            rf[instr_i[2:0]] <= res_o;
            // Non-synthesizable sim checks should be ignored safely
            ${'$'}display("step state=%0d res=%0h", state_q, res_o);
          end
        end : seq_regs

        assert property (@(posedge clk) rst_n |-> (busy_w == (state_q != ST_IDLE)));
      endmodule
      `end_keywords
    """.trimIndent()

    val parseRes = VerilogHdlParser.parse(hardSv)
    assertTrue("Expected complex SV parse success, got $parseRes", parseRes is VerilogParseResult.Success)
    val design = (parseRes as VerilogParseResult.Success).design
    assertEquals("hard_riscv_ctrl", design.topModule.name)
    assertEquals(4, design.topModule.instances.size)
    assertEquals(32, design.topModule.ports.first { it.name == "res_o" }.width)
    assertEquals(3, design.topModule.parameters.first { it.name == "IDX_W" }.value)

    val synthRes = RtlSynthesisEngine.synthesize(
      verilogSource = hardSv,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 10.0,
    )
    assertTrue("Expected synthesis success, got $synthRes", synthRes is SynthesisResult.Success)
    val report = (synthRes as SynthesisResult.Success).report
    assertTrue("Expected substantial synthesized cells, got ${report.cellCount}", report.cellCount >= 12)
    assertTrue("Expected sequential state/mac elements", report.seqCellCount >= 1)

    val simTrace = RtlSimulator.simulate(
      design = design,
      cycleCount = 8,
    )
    assertEquals(8, simTrace.cycleCount)
    assertTrue(simTrace.traces.isNotEmpty())
  }

  @Test
  fun largeMultiStageGenerateDesign_synthesizesAndCompletesFullGdsFlowQuickly() = runTest {
    // 16-lane pipelined DSP/ALU fabric generating a dense multi-hundred-gate netlist
    val bigRtl = """
      module pe_slice #(parameter W = 8) (
        input wire clk,
        input wire rst,
        input wire [W-1:0] x,
        input wire [W-1:0] y,
        input wire [W-1:0] acc_in,
        output reg [W-1:0] acc_out
      );
        always @(posedge clk) begin
          if (rst) acc_out <= {W{1'b0}};
          else acc_out <= acc_in + (x * y);
        end
      endmodule

      module systolic_fabric_16x #(
        parameter LANES = 16,
        parameter WIDTH = 8,
        parameter MODE_XOR = 1
      ) (
        input wire clk,
        input wire rst,
        input wire [WIDTH-1:0] in_data,
        input wire [WIDTH-1:0] in_weight,
        output wire [WIDTH-1:0] out_sum,
        output reg [WIDTH-1:0] out_reg,
        output wire parity_ok
      );
        wire [WIDTH-1:0] stage_bus [0:LANES-1];
        reg [WIDTH-1:0] pipe_r [0:LANES-1];
        wire [LANES-1:0] lane_bits;

        genvar g;
        generate
          for (g = 0; g < LANES; g = g + 1) begin : gen_pe
            wire [WIDTH-1:0] tap_w;
            pe_slice #(.W(WIDTH)) u_pe (
              .clk(clk),
              .rst(rst),
              .x(in_data ^ g),
              .y(in_weight + g),
              .acc_in(pipe_r[g]),
              .acc_out(tap_w)
            );
            assign lane_bits[g] = ^(tap_w);
            if (g % 2 == 0) begin : gen_even
              assign stage_bus[g] = tap_w + in_data;
            end else begin : gen_odd
              assign stage_bus[g] = (MODE_XOR != 0) ? (tap_w ^ in_weight) : (tap_w - in_weight);
            end
          end

          case (MODE_XOR)
            1: assign parity_ok = ^lane_bits;
            default: assign parity_ok = &lane_bits;
          endcase
        endgenerate

        integer k;
        always @(posedge clk) begin
          if (rst) begin
            out_reg <= 8'd0;
            for (k = 0; k < LANES; k = k + 1) begin
              pipe_r[k] <= 8'd0;
            end
          end else begin
            for (k = 0; k < LANES; k = k + 1) begin
              pipe_r[k] <= stage_bus[k] + ((in_data >>> 1) <<< 1);
            end
            out_reg <= pipe_r[LANES-1] + stage_bus[0] + stage_bus[LANES/2];
          end
        end

        assign out_sum = out_reg ^ stage_bus[LANES-1];
      endmodule
    """.trimIndent()

    val startMs = System.currentTimeMillis()
    val flowResult = RtlToGdsFlowEngine.runFlow(
      rtlSource = bigRtl,
      designName = "systolic_fabric_16x",
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      engineProfile = SynthesisEngineProfile.SILICON_FLOW,
      clockPeriodNs = 8.0,
      coreUtilizationPercent = 60,
      sdcText = "create_clock -name clk -period 8.0 [get_ports clk]",
      stepDelayMs = 0L,
      onProgress = {},
    )
    val elapsedMs = System.currentTimeMillis() - startMs

    assertTrue("Expected full RTL-to-GDS flow success, got failure", flowResult.isFinished)
    assertTrue("All 7 stages must pass", flowResult.stageStates.all { it.status == FlowStageStatus.PASSED })
    val layout = flowResult.layoutSnapshot
    assertNotNull(layout)
    layout!!
    assertTrue("Expected dense cell placement >= 40 cells, got ${layout.placedCells.size}", layout.placedCells.size >= 40)
    assertEquals("Expected 0 DRC violations even on large floorplan", 0, layout.drcViolations)
    assertTrue("Expected verified hierarchical GDS", layout.gdsVerified)
    assertTrue("Expected KLayout snapshot PNG output", layout.klayoutSnapshotPng.size > 1000)
    assertTrue("Flow should finish rapidly (<15s), took ${elapsedMs}ms", elapsedMs < 15_000L)
  }

  @Test
  fun escapedIdentifiersMultiPackedArraysAndVerilog1995Functions_parseCleanly() {
    val edgeRtl = """
      (* top = 1 *)
      module edge_cases_mod (clk, rst, bus_in, bus_out);
        input clk, rst;
        input [3:0][7:0] bus_in;
        output reg [3:0][7:0] bus_out;

        wire \escaped${'$'}wire_0 ;
        wire [7:0] chunk_w;
        wire [31:0] signed_const = 32 'shFFFF_0000;

        function [7:0] swap_nibbles;
          input [7:0] din;
          begin
            swap_nibbles = {din[3:0], din[7:4]};
          end
        endfunction

        assign \escaped${'$'}wire_0 = (bus_in[0] === 8'hFF);
        assign chunk_w = swap_nibbles(bus_in[8 +: 8]);

        always @(posedge clk) begin
          if (rst) begin
            bus_out <= '0;
          end else begin
            bus_out <= (\escaped${'$'}wire_0 ? signed_const : {24'b0, chunk_w});
          end
        end
      endmodule
    """.trimIndent()

    val res = VerilogHdlParser.parse(edgeRtl)
    assertTrue("Expected edge-case Verilog parse success, got $res", res is VerilogParseResult.Success)
    val mod = (res as VerilogParseResult.Success).design.topModule
    assertEquals("edge_cases_mod", mod.name)
    assertEquals(32, mod.ports.first { it.name == "bus_in" }.width)
    assertEquals(32, mod.ports.first { it.name == "bus_out" }.width)

    val synth = RtlSynthesisEngine.synthesize(
      verilogSource = edgeRtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.MIN_DELAY,
      clockPeriodNs = 2.5,
    )
    assertTrue("Expected synthesis success for edge cases, got $synth", synth is SynthesisResult.Success)
  }
}
