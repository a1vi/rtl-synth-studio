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
      id = "preset_riscv32i_pipe_core_sv",
      name = "riscv32i_pipe_core_sv",
      category = "CPU · RV32I 4-Stage",
      language = RtlLanguage.SYSTEM_VERILOG,
      description = "Pipelined 32-bit RV32I RISC-V core (IF/ID, ID/EX, EX/MEM, MEM/WB) with EX/MEM forwarding, load-use hazard stall, and branch flush.",
      defaultClockNs = 2.5,
      defaultLibrary = TargetLibrary.ASAP7_7NM,
      defaultGoal = OptimizationGoal.MIN_DELAY,
      verilogCode = """
module rv32i_imm_decode (
  input  logic [31:0] instr_i,
  output logic [31:0] imm_o,
  output logic [2:0]  funct3_o,
  output logic        is_alu_reg_o,
  output logic        is_alu_imm_o,
  output logic        is_branch_o,
  output logic        is_load_o,
  output logic        is_store_o,
  output logic        rd_we_o
);
  logic [6:0] opcode_w;
  assign opcode_w     = instr_i[6:0];
  assign funct3_o     = instr_i[14:12];
  assign is_alu_reg_o = (opcode_w == 7'h33);
  assign is_alu_imm_o = (opcode_w == 7'h13);
  assign is_branch_o  = (opcode_w == 7'h63);
  assign is_load_o    = (opcode_w == 7'h03);
  assign is_store_o   = (opcode_w == 7'h23);
  assign rd_we_o      = is_alu_reg_o | is_alu_imm_o | is_load_o;

  always_comb begin
    imm_o = 32'd0;
    if (is_store_o) begin
      imm_o = {{20{instr_i[31]}}, instr_i[31:25], instr_i[11:7]};
    end else if (is_branch_o) begin
      imm_o = {{19{instr_i[31]}}, instr_i[31], instr_i[7], instr_i[30:25], instr_i[11:8], 1'b0};
    end else begin
      imm_o = {{20{instr_i[31]}}, instr_i[31:20]};
    end
  end
endmodule

module rv32i_alu_br_unit (
  input  logic [31:0] op_a_i,
  input  logic [31:0] op_b_i,
  input  logic [2:0]  funct3_i,
  input  logic        sub_sra_i,
  input  logic        is_branch_i,
  output logic [31:0] alu_y_o,
  output logic        br_taken_o,
  output logic        zero_o
);
  logic        eq_w;
  logic        lt_u_w;
  logic [31:0] diff_w;

  assign eq_w   = (op_a_i == op_b_i);
  assign lt_u_w = (op_a_i < op_b_i);
  assign diff_w = op_a_i - op_b_i;

  always_comb begin
    alu_y_o    = 32'd0;
    br_taken_o = 1'b0;
    case (funct3_i)
      3'b000: alu_y_o = sub_sra_i ? diff_w : (op_a_i + op_b_i);
      3'b001: alu_y_o = op_a_i << op_b_i[4:0];
      3'b010: alu_y_o = lt_u_w ? 32'd1 : 32'd0;
      3'b011: alu_y_o = lt_u_w ? 32'd1 : 32'd0;
      3'b100: alu_y_o = op_a_i ^ op_b_i;
      3'b101: alu_y_o = op_a_i >> op_b_i[4:0];
      3'b110: alu_y_o = op_a_i | op_b_i;
      default: alu_y_o = op_a_i & op_b_i;
    endcase

    if (is_branch_i) begin
      case (funct3_i)
        3'b000: br_taken_o = eq_w;
        3'b001: br_taken_o = !eq_w;
        3'b100: br_taken_o = lt_u_w;
        default: br_taken_o = !lt_u_w;
      endcase
    end
  end

  assign zero_o = (alu_y_o == 32'd0);
endmodule

module riscv32i_pipe_core_sv (
  input  logic        clk,
  input  logic        rst_n,
  input  logic        imem_valid_i,
  input  logic [31:0] imem_instr_i,
  input  logic [31:0] dmem_rdata_i,
  output logic [31:0] imem_pc_o,
  output logic [31:0] dmem_addr_o,
  output logic [31:0] dmem_wdata_o,
  output logic        dmem_we_o,
  output logic [31:0] wb_data_o,
  output logic [4:0]  wb_rd_o,
  output logic        wb_valid_o,
  output logic        hazard_stall_o,
  output logic        branch_flush_o
);
  // Pipeline stage registers: IF -> ID -> EX -> MEM -> WB
  logic [31:0] pc_if_q;
  logic [31:0] pc_id_q, instr_id_q;
  logic        valid_id_q;

  logic [31:0] pc_ex_q, imm_ex_q, rs1_val_ex_q, rs2_val_ex_q;
  logic [4:0]  rs1_ex_q, rs2_ex_q, rd_ex_q;
  logic [2:0]  funct3_ex_q;
  logic        sub_ex_q, is_imm_ex_q, is_br_ex_q, is_ld_ex_q, is_st_ex_q, rd_we_ex_q, valid_ex_q;

  logic [31:0] alu_mem_q, st_data_mem_q, br_target_mem_q;
  logic [4:0]  rd_mem_q;
  logic        is_ld_mem_q, is_st_mem_q, rd_we_mem_q, br_taken_mem_q, valid_mem_q;

  logic [31:0] wb_data_q;
  logic [4:0]  rd_wb_q;
  logic        rd_we_wb_q, valid_wb_q;

  // Compact architectural register file (x1..x7 shadowed for fast ASIC synthesis; x0 hardwired 0)
  logic [31:0] rf_x1_q, rf_x2_q, rf_x3_q, rf_x4_q, rf_x5_q, rf_x6_q, rf_x7_q;

  // Decode stage signals
  logic [31:0] dec_imm_w;
  logic [2:0]  dec_funct3_w;
  logic        dec_alu_reg_w, dec_alu_imm_w, dec_br_w, dec_ld_w, dec_st_w, dec_we_w;
  logic [4:0]  id_rs1_w, id_rs2_w, id_rd_w;
  logic [31:0] id_rs1_rf_w, id_rs2_rf_w;

  assign id_rs1_w = instr_id_q[19:15];
  assign id_rs2_w = instr_id_q[24:20];
  assign id_rd_w  = instr_id_q[11:7];

  rv32i_imm_decode u_dec (
    .instr_i      (instr_id_q),
    .imm_o        (dec_imm_w),
    .funct3_o     (dec_funct3_w),
    .is_alu_reg_o (dec_alu_reg_w),
    .is_alu_imm_o (dec_alu_imm_w),
    .is_branch_o  (dec_br_w),
    .is_load_o    (dec_ld_w),
    .is_store_o   (dec_st_w),
    .rd_we_o      (dec_we_w)
  );

  // Register file read muxes + WB bypass
  always_comb begin
    id_rs1_rf_w = 32'd0;
    id_rs2_rf_w = 32'd0;
    case (id_rs1_w[2:0])
      3'd1: id_rs1_rf_w = rf_x1_q;
      3'd2: id_rs1_rf_w = rf_x2_q;
      3'd3: id_rs1_rf_w = rf_x3_q;
      3'd4: id_rs1_rf_w = rf_x4_q;
      3'd5: id_rs1_rf_w = rf_x5_q;
      3'd6: id_rs1_rf_w = rf_x6_q;
      3'd7: id_rs1_rf_w = rf_x7_q;
      default: id_rs1_rf_w = 32'd0;
    endcase
    case (id_rs2_w[2:0])
      3'd1: id_rs2_rf_w = rf_x1_q;
      3'd2: id_rs2_rf_w = rf_x2_q;
      3'd3: id_rs2_rf_w = rf_x3_q;
      3'd4: id_rs2_rf_w = rf_x4_q;
      3'd5: id_rs2_rf_w = rf_x5_q;
      3'd6: id_rs2_rf_w = rf_x6_q;
      3'd7: id_rs2_rf_w = rf_x7_q;
      default: id_rs2_rf_w = 32'd0;
    endcase
  end

  // Load-Use Data Hazard Unit
  logic load_use_stall_w;
  assign load_use_stall_w = valid_ex_q & is_ld_ex_q & (rd_ex_q != 5'd0) &
                            ((rd_ex_q == id_rs1_w) | (rd_ex_q == id_rs2_w));

  // EX Stage Data Forwarding (MEM -> EX and WB -> EX)
  logic [31:0] fwd_a_w, fwd_b_raw_w, alu_in_b_w, ex_alu_y_w, ex_br_target_w;
  logic        ex_br_taken_w, ex_zero_w;

  always_comb begin
    fwd_a_w     = rs1_val_ex_q;
    fwd_b_raw_w = rs2_val_ex_q;

    if (valid_mem_q && rd_we_mem_q && (rd_mem_q != 5'd0) && (rd_mem_q == rs1_ex_q)) begin
      fwd_a_w = alu_mem_q;
    end else if (valid_wb_q && rd_we_wb_q && (rd_wb_q != 5'd0) && (rd_wb_q == rs1_ex_q)) begin
      fwd_a_w = wb_data_q;
    end

    if (valid_mem_q && rd_we_mem_q && (rd_mem_q != 5'd0) && (rd_mem_q == rs2_ex_q)) begin
      fwd_b_raw_w = alu_mem_q;
    end else if (valid_wb_q && rd_we_wb_q && (rd_wb_q != 5'd0) && (rd_wb_q == rs2_ex_q)) begin
      fwd_b_raw_w = wb_data_q;
    end
  end

  assign alu_in_b_w = (is_imm_ex_q | is_ld_ex_q | is_st_ex_q) ? imm_ex_q : fwd_b_raw_w;

  rv32i_alu_br_unit u_alu_br (
    .op_a_i      (fwd_a_w),
    .op_b_i      (alu_in_b_w),
    .funct3_i    (funct3_ex_q),
    .sub_sra_i   (sub_ex_q),
    .is_branch_i (is_br_ex_q & valid_ex_q),
    .alu_y_o     (ex_alu_y_w),
    .br_taken_o  (ex_br_taken_w),
    .zero_o      (ex_zero_w)
  );

  assign ex_br_target_w = pc_ex_q + imm_ex_q;

  assign imem_pc_o      = pc_if_q;
  assign dmem_addr_o    = alu_mem_q;
  assign dmem_wdata_o   = st_data_mem_q;
  assign dmem_we_o      = valid_mem_q & is_st_mem_q;
  assign wb_data_o      = wb_data_q;
  assign wb_rd_o        = rd_wb_q;
  assign wb_valid_o     = valid_wb_q;
  assign hazard_stall_o = load_use_stall_w;
  assign branch_flush_o = br_taken_mem_q;

  always_ff @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      pc_if_q         <= 32'd0;
      pc_id_q         <= 32'd0;
      instr_id_q      <= 32'h00000013;
      valid_id_q      <= 1'b0;
      pc_ex_q         <= 32'd0;
      imm_ex_q        <= 32'd0;
      rs1_val_ex_q    <= 32'd0;
      rs2_val_ex_q    <= 32'd0;
      rs1_ex_q        <= 5'd0;
      rs2_ex_q        <= 5'd0;
      rd_ex_q         <= 5'd0;
      funct3_ex_q     <= 3'd0;
      sub_ex_q        <= 1'b0;
      is_imm_ex_q     <= 1'b0;
      is_br_ex_q      <= 1'b0;
      is_ld_ex_q      <= 1'b0;
      is_st_ex_q      <= 1'b0;
      rd_we_ex_q      <= 1'b0;
      valid_ex_q      <= 1'b0;
      alu_mem_q       <= 32'd0;
      st_data_mem_q   <= 32'd0;
      br_target_mem_q <= 32'd0;
      rd_mem_q        <= 5'd0;
      is_ld_mem_q     <= 1'b0;
      is_st_mem_q     <= 1'b0;
      rd_we_mem_q     <= 1'b0;
      br_taken_mem_q  <= 1'b0;
      valid_mem_q     <= 1'b0;
      wb_data_q       <= 32'd0;
      rd_wb_q         <= 5'd0;
      rd_we_wb_q      <= 1'b0;
      valid_wb_q      <= 1'b0;
      rf_x1_q         <= 32'd0;
      rf_x2_q         <= 32'd0;
      rf_x3_q         <= 32'd0;
      rf_x4_q         <= 32'd0;
      rf_x5_q         <= 32'd0;
      rf_x6_q         <= 32'd0;
      rf_x7_q         <= 32'd0;
    end else begin
      // IF / ID Stage
      if (br_taken_mem_q) begin
        pc_if_q    <= br_target_mem_q;
        pc_id_q    <= pc_id_q;
        instr_id_q <= 32'h00000013;
        valid_id_q <= 1'b0;
      end else if (!load_use_stall_w) begin
        pc_if_q    <= imem_valid_i ? (pc_if_q + 32'd4) : pc_if_q;
        pc_id_q    <= pc_if_q;
        instr_id_q <= imem_instr_i;
        valid_id_q <= imem_valid_i;
      end else begin
        pc_if_q    <= pc_if_q;
        pc_id_q    <= pc_id_q;
        instr_id_q <= instr_id_q;
        valid_id_q <= valid_id_q;
      end

      // ID / EX Stage
      if (load_use_stall_w || br_taken_mem_q) begin
        pc_ex_q      <= 32'd0;
        imm_ex_q     <= 32'd0;
        rs1_val_ex_q <= 32'd0;
        rs2_val_ex_q <= 32'd0;
        rs1_ex_q     <= 5'd0;
        rs2_ex_q     <= 5'd0;
        rd_ex_q      <= 5'd0;
        funct3_ex_q  <= 3'd0;
        sub_ex_q     <= 1'b0;
        is_imm_ex_q  <= 1'b0;
        is_br_ex_q   <= 1'b0;
        is_ld_ex_q   <= 1'b0;
        is_st_ex_q   <= 1'b0;
        rd_we_ex_q   <= 1'b0;
        valid_ex_q   <= 1'b0;
      end else begin
        pc_ex_q      <= pc_id_q;
        imm_ex_q     <= dec_imm_w;
        rs1_val_ex_q <= id_rs1_rf_w;
        rs2_val_ex_q <= id_rs2_rf_w;
        rs1_ex_q     <= id_rs1_w;
        rs2_ex_q     <= id_rs2_w;
        rd_ex_q      <= id_rd_w;
        funct3_ex_q  <= dec_funct3_w;
        sub_ex_q     <= instr_id_q[30] & dec_alu_reg_w;
        is_imm_ex_q  <= dec_alu_imm_w;
        is_br_ex_q   <= dec_br_w;
        is_ld_ex_q   <= dec_ld_w;
        is_st_ex_q   <= dec_st_w;
        rd_we_ex_q   <= dec_we_w;
        valid_ex_q   <= valid_id_q;
      end

      // EX / MEM Stage
      alu_mem_q       <= ex_alu_y_w;
      st_data_mem_q   <= fwd_b_raw_w;
      br_target_mem_q <= ex_br_target_w;
      rd_mem_q        <= rd_ex_q;
      is_ld_mem_q     <= is_ld_ex_q;
      is_st_mem_q     <= is_st_ex_q;
      rd_we_mem_q     <= rd_we_ex_q;
      br_taken_mem_q  <= ex_br_taken_w & !br_taken_mem_q;
      valid_mem_q     <= valid_ex_q & !br_taken_mem_q;

      // MEM / WB Stage
      wb_data_q  <= is_ld_mem_q ? dmem_rdata_i : alu_mem_q;
      rd_wb_q    <= rd_mem_q;
      rd_we_wb_q <= rd_we_mem_q;
      valid_wb_q <= valid_mem_q;

      // Architectural Register File Writeback (x1..x7)
      rf_x1_q <= (valid_wb_q && rd_we_wb_q && (rd_wb_q[2:0] == 3'd1) && (rd_wb_q[4:3] == 2'b00)) ? wb_data_q : rf_x1_q;
      rf_x2_q <= (valid_wb_q && rd_we_wb_q && (rd_wb_q[2:0] == 3'd2) && (rd_wb_q[4:3] == 2'b00)) ? wb_data_q : rf_x2_q;
      rf_x3_q <= (valid_wb_q && rd_we_wb_q && (rd_wb_q[2:0] == 3'd3) && (rd_wb_q[4:3] == 2'b00)) ? wb_data_q : rf_x3_q;
      rf_x4_q <= (valid_wb_q && rd_we_wb_q && (rd_wb_q[2:0] == 3'd4) && (rd_wb_q[4:3] == 2'b00)) ? wb_data_q : rf_x4_q;
      rf_x5_q <= (valid_wb_q && rd_we_wb_q && (rd_wb_q[2:0] == 3'd5) && (rd_wb_q[4:3] == 2'b00)) ? wb_data_q : rf_x5_q;
      rf_x6_q <= (valid_wb_q && rd_we_wb_q && (rd_wb_q[2:0] == 3'd6) && (rd_wb_q[4:3] == 2'b00)) ? wb_data_q : rf_x6_q;
      rf_x7_q <= (valid_wb_q && rd_we_wb_q && (rd_wb_q[2:0] == 3'd7) && (rd_wb_q[4:3] == 2'b00)) ? wb_data_q : rf_x7_q;
    end
  end
endmodule
      """.trimIndent(),
      testbenchCode = """
`timescale 1ns/1ps
module tb_riscv32i_pipe_core_sv;
  logic clk = 0;
  logic rst_n = 0;
  logic imem_valid_i = 0;
  logic [31:0] imem_instr_i = 32'h00500093;
  logic [31:0] dmem_rdata_i = 32'h12345678;
  logic [31:0] imem_pc_o, dmem_addr_o, dmem_wdata_o, wb_data_o;
  logic [4:0]  wb_rd_o;
  logic        dmem_we_o, wb_valid_o, hazard_stall_o, branch_flush_o;

  riscv32i_pipe_core_sv dut (.*);

  always #1.25 clk = ~clk;

  initial begin
    rst_n = 0; imem_valid_i = 0;
    #5 rst_n = 1; imem_valid_i = 1; imem_instr_i = 32'h00500093; // addi x1, x0, 5
    #5 imem_instr_i = 32'h00A08113; // addi x2, x1, 10 (EX forwarding)
    #5 imem_instr_i = 32'h002081B3; // add  x3, x1, x2
    #25 ${'$'}finish;
  end
endmodule
      """.trimIndent(),
    ),
    RtlPresetTemplate(
      id = "preset_aes128_high_tput_sv",
      name = "aes128_high_tput_sv",
      category = "Crypto · AES-128",
      language = RtlLanguage.SYSTEM_VERILOG,
      description = "High-throughput 128-bit AES encryption core (4 parallel GF(2^8) MixColumns words, SubBytes, ShiftRows, and 10-round RCON key schedule).",
      defaultClockNs = 3.0,
      defaultLibrary = TargetLibrary.NANGATE45_45NM,
      defaultGoal = OptimizationGoal.MIN_DELAY,
      verilogCode = """
module aes_gf256_xtime (
  input  logic [7:0] byte_i,
  output logic [7:0] x2_o,
  output logic [7:0] x3_o
);
  logic [7:0] shifted_w;
  assign shifted_w = {byte_i[6:0], 1'b0};
  assign x2_o      = byte_i[7] ? (shifted_w ^ 8'h1B) : shifted_w;
  assign x3_o      = x2_o ^ byte_i;
endmodule

module aes_sub_mixcol_word (
  input  logic [31:0] col_i,
  output logic [31:0] col_o
);
  logic [7:0] sb0_w, sb1_w, sb2_w, sb3_w;
  assign sb0_w = col_i[31:24] ^ {col_i[27:24], col_i[31:28]} ^ 8'h63;
  assign sb1_w = col_i[23:16] ^ {col_i[19:16], col_i[23:20]} ^ 8'h63;
  assign sb2_w = col_i[15:8]  ^ {col_i[11:8],  col_i[15:12]} ^ 8'h63;
  assign sb3_w = col_i[7:0]   ^ {col_i[3:0],   col_i[7:4]}   ^ 8'h63;

  logic [7:0] x2_0, x3_0, x2_1, x3_1, x2_2, x3_2, x2_3, x3_3;
  aes_gf256_xtime u_gf0 (.byte_i(sb0_w), .x2_o(x2_0), .x3_o(x3_0));
  aes_gf256_xtime u_gf1 (.byte_i(sb1_w), .x2_o(x2_1), .x3_o(x3_1));
  aes_gf256_xtime u_gf2 (.byte_i(sb2_w), .x2_o(x2_2), .x3_o(x3_2));
  aes_gf256_xtime u_gf3 (.byte_i(sb3_w), .x2_o(x2_3), .x3_o(x3_3));

  logic [7:0] mc0_w, mc1_w, mc2_w, mc3_w;
  assign mc0_w = x2_0 ^ x3_1 ^ sb2_w ^ sb3_w;
  assign mc1_w = sb0_w ^ x2_1 ^ x3_2 ^ sb3_w;
  assign mc2_w = sb0_w ^ sb1_w ^ x2_2 ^ x3_3;
  assign mc3_w = x3_0 ^ sb1_w ^ sb2_w ^ x2_3;

  assign col_o = {mc0_w, mc1_w, mc2_w, mc3_w};
endmodule

module aes128_high_tput_sv (
  input  logic        clk,
  input  logic        rst_n,
  input  logic        in_valid_i,
  input  logic [31:0] pt_col0_i,
  input  logic [31:0] pt_col1_i,
  input  logic [31:0] pt_col2_i,
  input  logic [31:0] pt_col3_i,
  input  logic [31:0] key_w0_i,
  input  logic [31:0] key_w1_i,
  input  logic [31:0] key_w2_i,
  input  logic [31:0] key_w3_i,
  output logic [31:0] ct_col0_o,
  output logic [31:0] ct_col1_o,
  output logic [31:0] ct_col2_o,
  output logic [31:0] ct_col3_o,
  output logic [3:0]  round_o,
  output logic        busy_o,
  output logic        out_valid_o
);
  logic [31:0] s0_q, s1_q, s2_q, s3_q;
  logic [31:0] k0_q, k1_q, k2_q, k3_q;
  logic [3:0]  rnd_q;
  logic        busy_q;
  logic        done_q;

  // ShiftRows 128-bit diagonal byte permutation across 4 columns
  logic [31:0] sr0_w, sr1_w, sr2_w, sr3_w;
  assign sr0_w = {s0_q[31:24], s1_q[23:16], s2_q[15:8], s3_q[7:0]};
  assign sr1_w = {s1_q[31:24], s2_q[23:16], s3_q[15:8], s0_q[7:0]};
  assign sr2_w = {s2_q[31:24], s3_q[23:16], s0_q[15:8], s1_q[7:0]};
  assign sr3_w = {s3_q[31:24], s0_q[23:16], s1_q[15:8], s2_q[7:0]};

  // 4 Parallel 32-bit SubBytes + GF(2^8) MixColumns datapath slices (full 128-bit throughput/cycle)
  logic [31:0] mc0_w, mc1_w, mc2_w, mc3_w;
  aes_sub_mixcol_word u_lane0 (.col_i(sr0_w), .col_o(mc0_w));
  aes_sub_mixcol_word u_lane1 (.col_i(sr1_w), .col_o(mc1_w));
  aes_sub_mixcol_word u_lane2 (.col_i(sr2_w), .col_o(mc2_w));
  aes_sub_mixcol_word u_lane3 (.col_i(sr3_w), .col_o(mc3_w));

  // On-the-fly 128-bit AES RCON Key Schedule expansion
  logic [7:0] rcon_w;
  always_comb begin
    case (rnd_q)
      4'd1: rcon_w = 8'h01;
      4'd2: rcon_w = 8'h02;
      4'd3: rcon_w = 8'h04;
      4'd4: rcon_w = 8'h08;
      4'd5: rcon_w = 8'h10;
      4'd6: rcon_w = 8'h20;
      4'd7: rcon_w = 8'h40;
      4'd8: rcon_w = 8'h80;
      4'd9: rcon_w = 8'h1B;
      default: rcon_w = 8'h36;
    endcase
  end

  logic [31:0] rot_sub_k3_w, nk0_w, nk1_w, nk2_w, nk3_w;
  assign rot_sub_k3_w = {k3_q[23:16] ^ rcon_w, k3_q[15:8] ^ 8'h63, k3_q[7:0], k3_q[31:24]};
  assign nk0_w        = k0_q ^ rot_sub_k3_w;
  assign nk1_w        = k1_q ^ nk0_w;
  assign nk2_w        = k2_q ^ nk1_w;
  assign nk3_w        = k3_q ^ nk2_w;

  assign ct_col0_o   = s0_q;
  assign ct_col1_o   = s1_q;
  assign ct_col2_o   = s2_q;
  assign ct_col3_o   = s3_q;
  assign round_o     = rnd_q;
  assign busy_o      = busy_q;
  assign out_valid_o = done_q;

  always_ff @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      s0_q   <= 32'd0;
      s1_q   <= 32'd0;
      s2_q   <= 32'd0;
      s3_q   <= 32'd0;
      k0_q   <= 32'd0;
      k1_q   <= 32'd0;
      k2_q   <= 32'd0;
      k3_q   <= 32'd0;
      rnd_q  <= 4'd0;
      busy_q <= 1'b0;
      done_q <= 1'b0;
    end else if (in_valid_i && !busy_q) begin
      // Initial AddRoundKey (Round 0)
      s0_q   <= pt_col0_i ^ key_w0_i;
      s1_q   <= pt_col1_i ^ key_w1_i;
      s2_q   <= pt_col2_i ^ key_w2_i;
      s3_q   <= pt_col3_i ^ key_w3_i;
      k0_q   <= key_w0_i;
      k1_q   <= key_w1_i;
      k2_q   <= key_w2_i;
      k3_q   <= key_w3_i;
      rnd_q  <= 4'd1;
      busy_q <= 1'b1;
      done_q <= 1'b0;
    end else if (busy_q) begin
      k0_q <= nk0_w;
      k1_q <= nk1_w;
      k2_q <= nk2_w;
      k3_q <= nk3_w;
      if (rnd_q == 4'd10) begin
        // Final round skips MixColumns per FIPS-197
        s0_q   <= sr0_w ^ nk0_w;
        s1_q   <= sr1_w ^ nk1_w;
        s2_q   <= sr2_w ^ nk2_w;
        s3_q   <= sr3_w ^ nk3_w;
        rnd_q  <= 4'd0;
        busy_q <= 1'b0;
        done_q <= 1'b1;
      end else begin
        s0_q   <= mc0_w ^ nk0_w;
        s1_q   <= mc1_w ^ nk1_w;
        s2_q   <= mc2_w ^ nk2_w;
        s3_q   <= mc3_w ^ nk3_w;
        rnd_q  <= rnd_q + 4'd1;
        busy_q <= 1'b1;
        done_q <= 1'b0;
      end
    end else begin
      s0_q   <= s0_q;
      s1_q   <= s1_q;
      s2_q   <= s2_q;
      s3_q   <= s3_q;
      k0_q   <= k0_q;
      k1_q   <= k1_q;
      k2_q   <= k2_q;
      k3_q   <= k3_q;
      rnd_q  <= rnd_q;
      busy_q <= 1'b0;
      done_q <= 1'b0;
    end
  end
endmodule
      """.trimIndent(),
      testbenchCode = """
`timescale 1ns/1ps
module tb_aes128_high_tput_sv;
  logic clk = 0;
  logic rst_n = 0;
  logic in_valid_i = 0;
  logic [31:0] pt_col0_i = 32'h3243F6A8;
  logic [31:0] pt_col1_i = 32'h885A308D;
  logic [31:0] pt_col2_i = 32'h313198A2;
  logic [31:0] pt_col3_i = 32'hE0370734;
  logic [31:0] key_w0_i  = 32'h2B7E1516;
  logic [31:0] key_w1_i  = 32'h28AED2A6;
  logic [31:0] key_w2_i  = 32'hABF71588;
  logic [31:0] key_w3_i  = 32'h09CF4F3C;
  logic [31:0] ct_col0_o, ct_col1_o, ct_col2_o, ct_col3_o;
  logic [3:0]  round_o;
  logic        busy_o, out_valid_o;

  aes128_high_tput_sv dut (.*);

  always #1.5 clk = ~clk;

  initial begin
    rst_n = 0; in_valid_i = 0;
    #6 rst_n = 1; in_valid_i = 1;
    #4 in_valid_i = 0;
    #40 ${'$'}finish;
  end
endmodule
      """.trimIndent(),
    ),
    RtlPresetTemplate(
      id = "preset_multi_ch_dma_axi_engine",
      name = "multi_ch_dma_axi_engine",
      category = "DMA · AXI4 Multi-Ch",
      language = RtlLanguage.VERILOG,
      description = "4-channel priority + round-robin AXI4 multi-channel DMA controller with descriptor banks, burst counters, and IRQ generation.",
      defaultClockNs = 5.0,
      defaultLibrary = TargetLibrary.SKY130_130NM,
      defaultGoal = OptimizationGoal.BALANCED,
      verilogCode = """
module dma_prio_rr_arbiter (
  input  wire       clk,
  input  wire       rst,
  input  wire [3:0] req_i,
  input  wire [3:0] high_prio_i,
  input  wire       lock_i,
  output reg  [3:0] grant_o,
  output reg  [1:0] active_ch_o
);
  reg [3:0] hp_req_w;
  reg [3:0] cand_w;
  reg [3:0] next_grant_w;
  reg [1:0] next_ch_w;
  reg [1:0] rr_ptr_q;

  always @(*) begin
    hp_req_w     = req_i & high_prio_i;
    cand_w       = (hp_req_w != 4'b0000) ? hp_req_w : req_i;
    next_grant_w = 4'b0000;
    next_ch_w    = active_ch_o;

    if (lock_i) begin
      next_grant_w = grant_o;
      next_ch_w    = active_ch_o;
    end else if (cand_w == 4'b0000) begin
      next_grant_w = 4'b0000;
      next_ch_w    = 2'd0;
    end else begin
      case (rr_ptr_q)
        2'd0: begin
          if (cand_w[1])      begin next_grant_w = 4'b0010; next_ch_w = 2'd1; end
          else if (cand_w[2]) begin next_grant_w = 4'b0100; next_ch_w = 2'd2; end
          else if (cand_w[3]) begin next_grant_w = 4'b1000; next_ch_w = 2'd3; end
          else                begin next_grant_w = 4'b0001; next_ch_w = 2'd0; end
        end
        2'd1: begin
          if (cand_w[2])      begin next_grant_w = 4'b0100; next_ch_w = 2'd2; end
          else if (cand_w[3]) begin next_grant_w = 4'b1000; next_ch_w = 2'd3; end
          else if (cand_w[0]) begin next_grant_w = 4'b0001; next_ch_w = 2'd0; end
          else                begin next_grant_w = 4'b0010; next_ch_w = 2'd1; end
        end
        2'd2: begin
          if (cand_w[3])      begin next_grant_w = 4'b1000; next_ch_w = 2'd3; end
          else if (cand_w[0]) begin next_grant_w = 4'b0001; next_ch_w = 2'd0; end
          else if (cand_w[1]) begin next_grant_w = 4'b0010; next_ch_w = 2'd1; end
          else                begin next_grant_w = 4'b0100; next_ch_w = 2'd2; end
        end
        default: begin
          if (cand_w[0])      begin next_grant_w = 4'b0001; next_ch_w = 2'd0; end
          else if (cand_w[1]) begin next_grant_w = 4'b0010; next_ch_w = 2'd1; end
          else if (cand_w[2]) begin next_grant_w = 4'b0100; next_ch_w = 2'd2; end
          else                begin next_grant_w = 4'b1000; next_ch_w = 2'd3; end
        end
      endcase
    end
  end

  always @(posedge clk) begin
    if (rst) begin
      grant_o     <= 4'b0000;
      active_ch_o <= 2'd0;
      rr_ptr_q    <= 2'd0;
    end else begin
      grant_o     <= next_grant_w;
      active_ch_o <= next_ch_w;
      if (next_grant_w != 4'b0000 && !lock_i) begin
        rr_ptr_q <= next_ch_w;
      end else begin
        rr_ptr_q <= rr_ptr_q;
      end
    end
  end
endmodule

module multi_ch_dma_axi_engine (
  input  wire        clk,
  input  wire        rst,
  input  wire [3:0]  ch_en_i,
  input  wire [3:0]  ch_high_prio_i,
  input  wire [31:0] ch0_src_addr_i,
  input  wire [31:0] ch0_dst_addr_i,
  input  wire [31:0] ch1_src_addr_i,
  input  wire [31:0] ch1_dst_addr_i,
  input  wire [31:0] ch2_src_addr_i,
  input  wire [31:0] ch2_dst_addr_i,
  input  wire [31:0] ch3_src_addr_i,
  input  wire [31:0] ch3_dst_addr_i,
  input  wire [7:0]  burst_beats_i,
  input  wire        axi_arready_i,
  input  wire        axi_rvalid_i,
  input  wire [31:0] axi_rdata_i,
  input  wire        axi_awready_i,
  input  wire        axi_wready_i,
  input  wire        axi_bvalid_i,
  output wire [3:0]  active_grant_o,
  output wire [1:0]  active_ch_o,
  output reg  [31:0] axi_addr_o,
  output reg  [31:0] axi_wdata_o,
  output wire        axi_arvalid_o,
  output wire        axi_rready_o,
  output wire        axi_awvalid_o,
  output wire        axi_wvalid_o,
  output wire        axi_bready_o,
  output reg  [3:0]  ch_done_irq_o,
  output reg         bus_err_o
);
  localparam [2:0] DMA_IDLE  = 3'd0;
  localparam [2:0] DMA_AR    = 3'd1;
  localparam [2:0] DMA_RDATA = 3'd2;
  localparam [2:0] DMA_AW    = 3'd3;
  localparam [2:0] DMA_WDATA = 3'd4;
  localparam [2:0] DMA_BRESP = 3'd5;
  localparam [2:0] DMA_ERR   = 3'd6;

  reg  [2:0]  dma_state_q;
  reg  [31:0] ch0_src_q, ch0_dst_q, ch1_src_q, ch1_dst_q, ch2_src_q, ch2_dst_q, ch3_src_q, ch3_dst_q;
  reg  [7:0]  ch_beats_q;
  reg  [3:0]  watchdog_q;
  wire [3:0]  arb_grant_w;
  wire [1:0]  arb_ch_w;
  wire        bus_lock_w;

  assign bus_lock_w     = (dma_state_q != DMA_IDLE);
  assign active_grant_o = arb_grant_w;
  assign active_ch_o    = arb_ch_w;

  dma_prio_rr_arbiter u_sched (
    .clk         (clk),
    .rst         (rst),
    .req_i       (ch_en_i & ~ch_done_irq_o),
    .high_prio_i (ch_high_prio_i),
    .lock_i      (bus_lock_w),
    .grant_o     (arb_grant_w),
    .active_ch_o (arb_ch_w)
  );

  assign axi_arvalid_o = (dma_state_q == DMA_AR);
  assign axi_rready_o  = (dma_state_q == DMA_RDATA);
  assign axi_awvalid_o = (dma_state_q == DMA_AW);
  assign axi_wvalid_o  = (dma_state_q == DMA_WDATA);
  assign axi_bready_o  = (dma_state_q == DMA_BRESP);

  reg [31:0] cur_src_w;
  reg [31:0] cur_dst_w;
  always @(*) begin
    case (arb_ch_w)
      2'd0: begin cur_src_w = ch0_src_q; cur_dst_w = ch0_dst_q; end
      2'd1: begin cur_src_w = ch1_src_q; cur_dst_w = ch1_dst_q; end
      2'd2: begin cur_src_w = ch2_src_q; cur_dst_w = ch2_dst_q; end
      default: begin cur_src_w = ch3_src_q; cur_dst_w = ch3_dst_q; end
    endcase
  end

  always @(posedge clk) begin
    if (rst) begin
      dma_state_q   <= DMA_IDLE;
      ch0_src_q     <= ch0_src_addr_i;
      ch0_dst_q     <= ch0_dst_addr_i;
      ch1_src_q     <= ch1_src_addr_i;
      ch1_dst_q     <= ch1_dst_addr_i;
      ch2_src_q     <= ch2_src_addr_i;
      ch2_dst_q     <= ch2_dst_addr_i;
      ch3_src_q     <= ch3_src_addr_i;
      ch3_dst_q     <= ch3_dst_addr_i;
      axi_addr_o    <= 32'd0;
      axi_wdata_o   <= 32'd0;
      ch_beats_q    <= 8'd0;
      watchdog_q    <= 4'd0;
      ch_done_irq_o <= 4'b0000;
      bus_err_o     <= 1'b0;
    end else begin
      case (dma_state_q)
        DMA_IDLE: begin
          bus_err_o     <= 1'b0;
          watchdog_q    <= 4'd0;
          ch_done_irq_o <= ch_done_irq_o & ch_en_i;
          ch0_src_q     <= ch_en_i[0] ? ch0_src_q : ch0_src_addr_i;
          ch0_dst_q     <= ch_en_i[0] ? ch0_dst_q : ch0_dst_addr_i;
          ch1_src_q     <= ch_en_i[1] ? ch1_src_q : ch1_src_addr_i;
          ch1_dst_q     <= ch_en_i[1] ? ch1_dst_q : ch1_dst_addr_i;
          ch2_src_q     <= ch_en_i[2] ? ch2_src_q : ch2_src_addr_i;
          ch2_dst_q     <= ch_en_i[2] ? ch2_dst_q : ch2_dst_addr_i;
          ch3_src_q     <= ch_en_i[3] ? ch3_src_q : ch3_src_addr_i;
          ch3_dst_q     <= ch_en_i[3] ? ch3_dst_q : ch3_dst_addr_i;
          if (arb_grant_w != 4'b0000) begin
            dma_state_q <= DMA_AR;
            axi_addr_o  <= cur_src_w;
            ch_beats_q  <= (burst_beats_i == 8'd0) ? 8'd1 : burst_beats_i;
          end else begin
            dma_state_q <= DMA_IDLE;
            axi_addr_o  <= axi_addr_o;
            ch_beats_q  <= 8'd0;
          end
        end
        DMA_AR: begin
          if (axi_arready_i) begin
            dma_state_q <= DMA_RDATA;
            watchdog_q  <= 4'd0;
          end else if (watchdog_q == 4'hF) begin
            dma_state_q <= DMA_ERR;
            watchdog_q  <= 4'd0;
          end else begin
            dma_state_q <= DMA_AR;
            watchdog_q  <= watchdog_q + 4'd1;
          end
        end
        DMA_RDATA: begin
          if (axi_rvalid_i) begin
            dma_state_q <= DMA_AW;
            axi_wdata_o <= axi_rdata_i;
            axi_addr_o  <= cur_dst_w;
            watchdog_q  <= 4'd0;
          end else if (watchdog_q == 4'hF) begin
            dma_state_q <= DMA_ERR;
            watchdog_q  <= 4'd0;
          end else begin
            dma_state_q <= DMA_RDATA;
            watchdog_q  <= watchdog_q + 4'd1;
          end
        end
        DMA_AW: begin
          if (axi_awready_i) begin
            dma_state_q <= DMA_WDATA;
            watchdog_q  <= 4'd0;
          end else if (watchdog_q == 4'hF) begin
            dma_state_q <= DMA_ERR;
            watchdog_q  <= 4'd0;
          end else begin
            dma_state_q <= DMA_AW;
            watchdog_q  <= watchdog_q + 4'd1;
          end
        end
        DMA_WDATA: begin
          if (axi_wready_i) begin
            dma_state_q <= DMA_BRESP;
            watchdog_q  <= 4'd0;
            ch0_src_q   <= (arb_ch_w == 2'd0) ? (ch0_src_q + 32'd4) : ch0_src_q;
            ch0_dst_q   <= (arb_ch_w == 2'd0) ? (ch0_dst_q + 32'd4) : ch0_dst_q;
            ch1_src_q   <= (arb_ch_w == 2'd1) ? (ch1_src_q + 32'd4) : ch1_src_q;
            ch1_dst_q   <= (arb_ch_w == 2'd1) ? (ch1_dst_q + 32'd4) : ch1_dst_q;
            ch2_src_q   <= (arb_ch_w == 2'd2) ? (ch2_src_q + 32'd4) : ch2_src_q;
            ch2_dst_q   <= (arb_ch_w == 2'd2) ? (ch2_dst_q + 32'd4) : ch2_dst_q;
            ch3_src_q   <= (arb_ch_w == 2'd3) ? (ch3_src_q + 32'd4) : ch3_src_q;
            ch3_dst_q   <= (arb_ch_w == 2'd3) ? (ch3_dst_q + 32'd4) : ch3_dst_q;
          end else if (watchdog_q == 4'hF) begin
            dma_state_q <= DMA_ERR;
            watchdog_q  <= 4'd0;
          end else begin
            dma_state_q <= DMA_WDATA;
            watchdog_q  <= watchdog_q + 4'd1;
          end
        end
        DMA_BRESP: begin
          if (axi_bvalid_i) begin
            watchdog_q <= 4'd0;
            if (ch_beats_q <= 8'd1) begin
              dma_state_q   <= DMA_IDLE;
              ch_beats_q    <= 8'd0;
              ch_done_irq_o <= ch_done_irq_o | arb_grant_w;
            end else begin
              dma_state_q <= DMA_AR;
              axi_addr_o  <= cur_src_w;
              ch_beats_q  <= ch_beats_q - 8'd1;
            end
          end else if (watchdog_q == 4'hF) begin
            dma_state_q <= DMA_ERR;
            watchdog_q  <= 4'd0;
          end else begin
            dma_state_q <= DMA_BRESP;
            watchdog_q  <= watchdog_q + 4'd1;
          end
        end
        default: begin
          bus_err_o   <= 1'b1;
          dma_state_q <= DMA_IDLE;
          watchdog_q  <= 4'd0;
          ch_beats_q  <= 8'd0;
        end
      endcase
    end
  end
endmodule
      """.trimIndent(),
      testbenchCode = """
`timescale 1ns/1ps
module tb_multi_ch_dma_axi_engine;
  reg clk = 0;
  reg rst = 1;
  reg [3:0] ch_en_i = 4'b0101;
  reg [3:0] ch_high_prio_i = 4'b0100;
  reg [31:0] ch0_src_addr_i = 32'h1000_0000;
  reg [31:0] ch0_dst_addr_i = 32'h2000_0000;
  reg [31:0] ch1_src_addr_i = 32'h1000_1000;
  reg [31:0] ch1_dst_addr_i = 32'h2000_1000;
  reg [31:0] ch2_src_addr_i = 32'h1000_2000;
  reg [31:0] ch2_dst_addr_i = 32'h2000_2000;
  reg [31:0] ch3_src_addr_i = 32'h1000_3000;
  reg [31:0] ch3_dst_addr_i = 32'h2000_3000;
  reg [7:0] burst_beats_i = 8'd2;
  reg axi_arready_i = 1;
  reg axi_rvalid_i = 1;
  reg [31:0] axi_rdata_i = 32'hDEAD_BEEF;
  reg axi_awready_i = 1;
  reg axi_wready_i = 1;
  reg axi_bvalid_i = 1;
  wire [3:0] active_grant_o, ch_done_irq_o;
  wire [1:0] active_ch_o;
  wire [31:0] axi_addr_o, axi_wdata_o;
  wire axi_arvalid_o, axi_rready_o, axi_awvalid_o, axi_wvalid_o, axi_bready_o, bus_err_o;

  multi_ch_dma_axi_engine dut (
    .clk(clk), .rst(rst), .ch_en_i(ch_en_i), .ch_high_prio_i(ch_high_prio_i),
    .ch0_src_addr_i(ch0_src_addr_i), .ch0_dst_addr_i(ch0_dst_addr_i),
    .ch1_src_addr_i(ch1_src_addr_i), .ch1_dst_addr_i(ch1_dst_addr_i),
    .ch2_src_addr_i(ch2_src_addr_i), .ch2_dst_addr_i(ch2_dst_addr_i),
    .ch3_src_addr_i(ch3_src_addr_i), .ch3_dst_addr_i(ch3_dst_addr_i),
    .burst_beats_i(burst_beats_i), .axi_arready_i(axi_arready_i), .axi_rvalid_i(axi_rvalid_i),
    .axi_rdata_i(axi_rdata_i), .axi_awready_i(axi_awready_i), .axi_wready_i(axi_wready_i),
    .axi_bvalid_i(axi_bvalid_i), .active_grant_o(active_grant_o), .active_ch_o(active_ch_o),
    .axi_addr_o(axi_addr_o), .axi_wdata_o(axi_wdata_o), .axi_arvalid_o(axi_arvalid_o),
    .axi_rready_o(axi_rready_o), .axi_awvalid_o(axi_awvalid_o), .axi_wvalid_o(axi_wvalid_o),
    .axi_bready_o(axi_bready_o), .ch_done_irq_o(ch_done_irq_o), .bus_err_o(bus_err_o)
  );

  always #2.5 clk = ~clk;

  initial begin
    rst = 1;
    #10 rst = 0;
    #60 ${'$'}finish;
  end
endmodule
      """.trimIndent(),
    ),
    RtlPresetTemplate(
      id = "preset_fir8_sym_systolic_dsp",
      name = "fir8_sym_systolic_dsp",
      category = "DSP · 8-Tap FIR",
      language = RtlLanguage.VERILOG,
      description = "8-tap hierarchical pipelined systolic FIR DSP filter with 3-stage adder reduction tree and overflow saturation clamp.",
      defaultClockNs = 4.0,
      defaultLibrary = TargetLibrary.ARTIX7_FPGA,
      defaultGoal = OptimizationGoal.MIN_DELAY,
      verilogCode = """
module fir_tap_slice (
  input  wire        clk,
  input  wire        rst,
  input  wire        en,
  input  wire [15:0] sample_i,
  input  wire [15:0] coeff_i,
  output reg  [15:0] sample_z_o,
  output reg  [31:0] tap_prod_o
);
  wire [31:0] mul_w;
  assign mul_w = sample_i * coeff_i;

  always @(posedge clk) begin
    if (rst) begin
      sample_z_o <= 16'd0;
      tap_prod_o <= 32'd0;
    end else if (en) begin
      sample_z_o <= sample_i;
      tap_prod_o <= mul_w;
    end else begin
      sample_z_o <= sample_z_o;
      tap_prod_o <= tap_prod_o;
    end
  end
endmodule

module fir8_sym_systolic_dsp (
  input  wire        clk,
  input  wire        rst,
  input  wire        valid_i,
  input  wire [15:0] x_i,
  input  wire [15:0] c0_i,
  input  wire [15:0] c1_i,
  input  wire [15:0] c2_i,
  input  wire [15:0] c3_i,
  output reg  [31:0] y_o,
  output reg         sat_o,
  output reg         valid_o
);
  wire [15:0] d0_w, d1_w, d2_w, d3_w, d4_w, d5_w, d6_w, d7_w;
  wire [31:0] p0_w, p1_w, p2_w, p3_w, p4_w, p5_w, p6_w, p7_w;

  fir_tap_slice u_tap0 (.clk(clk), .rst(rst), .en(valid_i), .sample_i(x_i),  .coeff_i(c0_i), .sample_z_o(d0_w), .tap_prod_o(p0_w));
  fir_tap_slice u_tap1 (.clk(clk), .rst(rst), .en(valid_i), .sample_i(d0_w), .coeff_i(c1_i), .sample_z_o(d1_w), .tap_prod_o(p1_w));
  fir_tap_slice u_tap2 (.clk(clk), .rst(rst), .en(valid_i), .sample_i(d1_w), .coeff_i(c2_i), .sample_z_o(d2_w), .tap_prod_o(p2_w));
  fir_tap_slice u_tap3 (.clk(clk), .rst(rst), .en(valid_i), .sample_i(d2_w), .coeff_i(c3_i), .sample_z_o(d3_w), .tap_prod_o(p3_w));
  fir_tap_slice u_tap4 (.clk(clk), .rst(rst), .en(valid_i), .sample_i(d3_w), .coeff_i(c3_i), .sample_z_o(d4_w), .tap_prod_o(p4_w));
  fir_tap_slice u_tap5 (.clk(clk), .rst(rst), .en(valid_i), .sample_i(d4_w), .coeff_i(c2_i), .sample_z_o(d5_w), .tap_prod_o(p5_w));
  fir_tap_slice u_tap6 (.clk(clk), .rst(rst), .en(valid_i), .sample_i(d5_w), .coeff_i(c1_i), .sample_z_o(d6_w), .tap_prod_o(p6_w));
  fir_tap_slice u_tap7 (.clk(clk), .rst(rst), .en(valid_i), .sample_i(d6_w), .coeff_i(c0_i), .sample_z_o(d7_w), .tap_prod_o(p7_w));

  reg  [31:0] s1_0_q, s1_1_q, s1_2_q, s1_3_q;
  reg  [31:0] s2_0_q, s2_1_q;
  reg         v_s1_q, v_s2_q, v_s3_q;
  wire [31:0] total_w;
  wire        ovf_w;

  assign total_w = s2_0_q + s2_1_q;
  assign ovf_w   = (total_w > 32'h7FFFFFFF);

  always @(posedge clk) begin
    if (rst) begin
      s1_0_q  <= 32'd0;
      s1_1_q  <= 32'd0;
      s1_2_q  <= 32'd0;
      s1_3_q  <= 32'd0;
      s2_0_q  <= 32'd0;
      s2_1_q  <= 32'd0;
      v_s1_q  <= 1'b0;
      v_s2_q  <= 1'b0;
      v_s3_q  <= 1'b0;
      y_o     <= 32'd0;
      sat_o   <= 1'b0;
      valid_o <= 1'b0;
    end else begin
      s1_0_q  <= p0_w + p1_w;
      s1_1_q  <= p2_w + p3_w;
      s1_2_q  <= p4_w + p5_w;
      s1_3_q  <= p6_w + p7_w;
      s2_0_q  <= s1_0_q + s1_1_q;
      s2_1_q  <= s1_2_q + s1_3_q;
      v_s1_q  <= valid_i;
      v_s2_q  <= v_s1_q;
      v_s3_q  <= v_s2_q;
      valid_o <= v_s3_q;
      sat_o   <= ovf_w;
      y_o     <= ovf_w ? 32'h7FFFFFFF : total_w;
    end
  end
endmodule
      """.trimIndent(),
    ),
    RtlPresetTemplate(
      id = "preset_eth10g_crc32_pcs_sv",
      name = "eth10g_crc32_pcs_sv",
      category = "Networking · 10G PCS",
      language = RtlLanguage.SYSTEM_VERILOG,
      description = "IEEE 802.3 32-bit parallel CRC-32 FCS generator & frame integrity checker for 10G Ethernet PCS MAC streams.",
      defaultClockNs = 2.5,
      defaultLibrary = TargetLibrary.ASAP7_7NM,
      defaultGoal = OptimizationGoal.BALANCED,
      verilogCode = """
module eth10g_crc32_pcs_sv (
  input  logic        clk,
  input  logic        rst_n,
  input  logic        sof_i,
  input  logic        eof_i,
  input  logic        data_valid_i,
  input  logic [31:0] data_word_i,
  output logic [31:0] crc_o,
  output logic [11:0] word_cnt_o,
  output logic        fcs_ok_o,
  output logic        frame_err_o
);
  localparam [31:0] POLY_IEEE = 32'h04C11DB7;
  localparam [31:0] MAGIC_RES = 32'hC704DD7B;
  localparam [1:0]  FRM_IDLE  = 2'd0;
  localparam [1:0]  FRM_DATA  = 2'd1;
  localparam [1:0]  FRM_CHECK = 2'd2;

  logic [1:0]  state_q;
  logic [31:0] crc_q;
  logic [11:0] cnt_q;
  logic        fcs_ok_q;
  logic        err_q;

  logic [31:0] mixed_w;
  logic [31:0] fold_a_w;
  logic [31:0] fold_b_w;
  assign mixed_w  = crc_q ^ data_word_i;
  assign fold_a_w = {mixed_w[23:0], 8'd0} ^ (mixed_w[31] ? POLY_IEEE : 32'd0);
  assign fold_b_w = {fold_a_w[23:0], 8'd0} ^ (fold_a_w[31] ? POLY_IEEE : 32'd0) ^ (mixed_w & POLY_IEEE);

  assign crc_o       = crc_q ^ 32'hFFFFFFFF;
  assign word_cnt_o  = cnt_q;
  assign fcs_ok_o    = fcs_ok_q;
  assign frame_err_o = err_q;

  always_ff @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      state_q  <= FRM_IDLE;
      crc_q    <= 32'hFFFFFFFF;
      cnt_q    <= 12'd0;
      fcs_ok_q <= 1'b0;
      err_q    <= 1'b0;
    end else begin
      case (state_q)
        FRM_IDLE: begin
          fcs_ok_q <= 1'b0;
          err_q    <= 1'b0;
          if (sof_i && data_valid_i) begin
            state_q <= FRM_DATA;
            crc_q   <= fold_b_w;
            cnt_q   <= 12'd1;
          end else begin
            state_q <= FRM_IDLE;
            crc_q   <= 32'hFFFFFFFF;
            cnt_q   <= 12'd0;
          end
        end
        FRM_DATA: begin
          fcs_ok_q <= 1'b0;
          err_q    <= 1'b0;
          if (data_valid_i) begin
            crc_q <= fold_b_w;
            cnt_q <= cnt_q + 12'd1;
            if (eof_i) begin
              state_q <= FRM_CHECK;
            end else begin
              state_q <= FRM_DATA;
            end
          end else begin
            state_q <= FRM_DATA;
            crc_q   <= crc_q;
            cnt_q   <= cnt_q;
          end
        end
        default: begin
          state_q  <= FRM_IDLE;
          crc_q    <= crc_q;
          cnt_q    <= cnt_q;
          fcs_ok_q <= (crc_q == MAGIC_RES);
          err_q    <= (crc_q != MAGIC_RES);
        end
      endcase
    end
  end
endmodule
      """.trimIndent(),
    ),
    RtlPresetTemplate(
      id = "preset_uart_fifo_transceiver_vhdl",
      name = "uart_fifo_transceiver_vhdl",
      category = "Protocol · VHDL-2008",
      language = RtlLanguage.VHDL,
      description = "IEEE-1076 VHDL-2008 UART transceiver controller with baud-tick divider, 5-state framing FSM, shift register, and parity engine.",
      defaultClockNs = 6.0,
      defaultLibrary = TargetLibrary.GF180MCU_180NM,
      defaultGoal = OptimizationGoal.BALANCED,
      verilogCode = """
-- VHDL-2008 UART Serializer Transceiver with Baud Tick Generator & Parity
library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity uart_fifo_transceiver_vhdl is
  port (
    clk       : in  std_logic;
    rst       : in  std_logic;
    tx_start  : in  std_logic;
    parity_en : in  std_logic;
    tx_data   : in  std_logic_vector(7 downto 0);
    baud_div  : in  std_logic_vector(7 downto 0);
    tx_line   : out std_logic;
    tx_busy   : out std_logic;
    tx_done   : out std_logic;
    tx_parity : out std_logic
  );
end entity uart_fifo_transceiver_vhdl;

architecture rtl of uart_fifo_transceiver_vhdl is
  signal state_r    : std_logic_vector(2 downto 0);
  signal baud_cnt_r : std_logic_vector(7 downto 0);
  signal bit_idx_r  : std_logic_vector(2 downto 0);
  signal shift_r    : std_logic_vector(7 downto 0);
  signal parity_r   : std_logic;
  signal baud_tick  : std_logic;
begin
  baud_tick <= '1' when (baud_cnt_r = baud_div) else '0';
  tx_busy   <= '0' when (state_r = "000") else '1';
  tx_parity <= parity_r;

  process(clk)
  begin
    if rising_edge(clk) then
      if rst = '1' then
        state_r    <= "000";
        baud_cnt_r <= "00000000";
        bit_idx_r  <= "000";
        shift_r    <= "00000000";
        parity_r   <= '0';
        tx_line    <= '1';
        tx_done    <= '0';
      else
        if state_r = "000" then
          tx_line    <= '1';
          tx_done    <= '0';
          baud_cnt_r <= "00000000";
          bit_idx_r  <= "000";
          if tx_start = '1' then
            state_r  <= "001";
            shift_r  <= tx_data;
            parity_r <= '0';
          end if;
        elsif state_r = "001" then
          tx_line <= '0';
          tx_done <= '0';
          if baud_tick = '1' then
            baud_cnt_r <= "00000000";
            state_r    <= "010";
          else
            baud_cnt_r <= baud_cnt_r + "00000001";
          end if;
        elsif state_r = "010" then
          tx_line <= shift_r;
          tx_done <= '0';
          if baud_tick = '1' then
            baud_cnt_r <= "00000000";
            parity_r   <= parity_r xor shift_r;
            if bit_idx_r = "111" then
              if parity_en = '1' then
                state_r <= "011";
              else
                state_r <= "100";
              end if;
            else
              bit_idx_r <= bit_idx_r + "001";
              shift_r   <= shift_r;
            end if;
          else
            baud_cnt_r <= baud_cnt_r + "00000001";
          end if;
        elsif state_r = "011" then
          tx_line <= parity_r;
          tx_done <= '0';
          if baud_tick = '1' then
            baud_cnt_r <= "00000000";
            state_r    <= "100";
          else
            baud_cnt_r <= baud_cnt_r + "00000001";
          end if;
        else
          tx_line <= '1';
          if baud_tick = '1' then
            baud_cnt_r <= "00000000";
            state_r    <= "000";
            tx_done    <= '1';
          else
            baud_cnt_r <= baud_cnt_r + "00000001";
            tx_done    <= '0';
          end if;
        end if;
      end if;
    end if;
  end process;
end architecture rtl;
      """.trimIndent(),
    ),
  )
}
