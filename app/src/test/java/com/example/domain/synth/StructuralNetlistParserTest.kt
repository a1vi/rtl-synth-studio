package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuralNetlistParserTest {

  @Test
  fun parseVerilogNetlist_identifiesStandardCellsPinsAndNetsForAllSupportedGates() {
    val structuralNetlist = """
      module gate_mix (
        input clk,
        input rst,
        input [3:0] a,
        input [3:0] b,
        input sel,
        output [3:0] y
      );
        wire [3:0] w_and, w_or, w_not, w_nand, w_nor, w_xor, w_xnor, w_mux, w_add, w_cmp, w_buf;
        AND2x1_ASAP7 u_and (.A(a), .B(b), .Y(w_and));
        OR2x1_ASAP7 u_or (.A(w_and), .B(b), .Y(w_or));
        INVx1_ASAP7 u_inv (.A(w_or), .Y(w_not));
        NAND2x1_ASAP7 u_nand (.A(w_not), .B(a), .Y(w_nand));
        NOR2x1_ASAP7 u_nor (.A(w_nand), .B(b), .Y(w_nor));
        XOR2x1_ASAP7 u_xor (.A(w_nor), .B(a), .Y(w_xor));
        XNOR2x1_ASAP7 u_xnor (.A(w_xor), .B(b), .Y(w_xnor));
        MUX2x1_ASAP7 u_mux (.S(sel), .A(w_xnor), .B(a), .Y(w_mux));
        ADDx1_ASAP7 u_add (.A(w_mux), .B(b), .Y(w_add));
        CMPx1_ASAP7 u_cmp (.A(w_add), .B(b), .Y(w_cmp));
        BUFx2_ASAP7 u_buf (.A(w_cmp), .Y(w_buf));
        DFFRx1_ASAP7 u_reg (.D(w_buf), .CLK(clk), .RST(rst), .Q(y));
      endmodule
    """.trimIndent()

    val report = SynthesisReport(
      moduleName = "gate_mix",
      ports = emptyList(),
      targetLibrary = TargetLibrary.ASAP7_7NM,
      optimizationGoal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
      nodes = emptyList(),
      edges = emptyList(),
      criticalPath = emptyList(),
      criticalPathDelayNs = 1.6,
      wnsNs = 0.4,
      tnsNs = 0.0,
      fmaxMhz = 500.0,
      totalAreaUm2 = 24.0,
      cellCount = 12,
      combCellCount = 11,
      seqCellCount = 1,
      dynamicPowerMw = 0.7,
      leakagePowerMw = 0.05,
      totalPowerMw = 0.75,
      gateHistogram = emptyMap(),
      netlistVerilog = structuralNetlist,
      synthesisLog = emptyList(),
      warnings = emptyList(),
      preOptCellCount = 12,
      postOptCellCount = 12,
      synthesisDurationMs = 12,
      engineProfile = com.example.data.model.SynthesisEngineProfile.SILICON_FLOW,
      switchingPowerMw = 0.5,
      internalPowerMw = 0.2,
    )

    val graph = StructuralNetlistParser.buildSchematicGraph(report)
    val categories = graph.cells.associate { it.instanceName to it.category }

    assertEquals(SchematicCellCategory.AND, categories["u_and"])
    assertEquals(SchematicCellCategory.OR, categories["u_or"])
    assertEquals(SchematicCellCategory.NOT, categories["u_inv"])
    assertEquals(SchematicCellCategory.NAND, categories["u_nand"])
    assertEquals(SchematicCellCategory.NOR, categories["u_nor"])
    assertEquals(SchematicCellCategory.XOR, categories["u_xor"])
    assertEquals(SchematicCellCategory.XNOR, categories["u_xnor"])
    assertEquals(SchematicCellCategory.MUX, categories["u_mux"])
    assertEquals(SchematicCellCategory.ADDER, categories["u_add"])
    assertEquals(SchematicCellCategory.CMP, categories["u_cmp"])
    assertEquals(SchematicCellCategory.BUF, categories["u_buf"])
    assertEquals(SchematicCellCategory.DFF, categories["u_reg"])

    val regCell = graph.cells.first { it.instanceName == "u_reg" }
    assertEquals(listOf("D", "CLK", "RST"), regCell.inputPins.map { it.pinName })
    assertEquals(listOf("Q"), regCell.outputPins.map { it.pinName })

    val netWAnd = graph.netByName["w_and"]
    assertNotNull(netWAnd)
    assertTrue(netWAnd!!.loadPins.any { it.second == "A" })

    val hitCells = StructuralNetlistParser.searchCellsAndNets(graph, "u_xnor")
    assertTrue(hitCells.any { it is SchematicSearchHit.CellHit && it.cell.instanceName == "u_xnor" })

    val hitNets = StructuralNetlistParser.searchCellsAndNets(graph, "w_mux")
    assertTrue(hitNets.any { it is SchematicSearchHit.NetHit && it.net.name == "w_mux" })
  }

  @Test
  fun hierarchicalRtlDesign_buildsHierarchyTreeAndSlicesSubmoduleScope() {
    val hierRtl = """
      module leaf_and(input a, input b, output y);
        assign y = a & b;
      endmodule

      module top_hier(input clk, input a, input b, output reg q);
        wire w;
        leaf_and u_leaf (.a(a), .b(b), .y(w));
        always @(posedge clk) q <= w;
      endmodule
    """.trimIndent()

    val parsed = (VerilogHdlParser.parse(hierRtl) as VerilogParseResult.Success).design
    val synth = RtlSynthesisEngine.synthesize(
      verilogSource = hierRtl,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
    ) as SynthesisResult.Success

    val topGraph = StructuralNetlistParser.buildSchematicGraph(synth.report, parsed, scopePath = "")
    assertEquals("top_hier", topGraph.hierarchyRoot.moduleType)
    assertTrue(topGraph.hierarchyRoot.children.any { it.instanceName == "u_leaf" && it.moduleType == "leaf_and" })

    val leafGraph = StructuralNetlistParser.buildSchematicGraph(synth.report, parsed, scopePath = "u_leaf")
    assertEquals("u_leaf", leafGraph.scopePath)
    val internalLeafCells = leafGraph.cells.filter { !it.category.isPort }
    assertTrue(internalLeafCells.isNotEmpty())
    assertTrue(internalLeafCells.all { it.hierarchyPath.startsWith("u_leaf") })
    assertTrue(leafGraph.cells.any { it.category == SchematicCellCategory.INPUT_PORT })
    assertTrue(leafGraph.cells.any { it.category == SchematicCellCategory.OUTPUT_PORT })
  }
}
