package com.example.domain.synth

import com.example.data.model.TargetLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanChainStitcherTest {

  private val rawStructuralNetlist = """
    module pipeline_reg (clk, rst, d0, d1, d2, d3, y);
      input clk;
      input rst;
      input d0;
      input d1;
      input d2;
      input d3;
      output y;

      wire w_ff0;
      wire w_ff1;
      wire w_ff2;
      wire w_comb;

      DFFR_X1 u_ff0 ( .CLK(clk), .RST(rst), .D(d0), .Q(w_ff0) );
      DFFR_X1 u_ff1 ( .CLK(clk), .RST(rst), .D(w_ff0), .Q(w_ff1) );
      DFFR_X1 u_ff2 ( .CLK(clk), .RST(rst), .D(w_ff1), .Q(w_ff2) );
      XOR2_X1 u_xor ( .A(w_ff2), .B(d3), .Y(w_comb) );
      DFF_X1 u_ff3 ( .CLK(clk), .D(w_comb), .Q(y) );
    endmodule
  """.trimIndent()

  @Test
  fun buildScanPortDefinitions_avoidsCollisionsAndEmitsValidDeclarations() {
    val defs = ScanChainStitcher.buildScanPortDefinitions(
      existingPortNames = setOf("clk", "rst", "scan_in_0"),
      chainCount = 2,
      includeJtagTap = true,
      includeMbist = true,
    )

    assertTrue(defs.any { it.role == ScanPortRole.SCAN_ENABLE && it.name == "scan_en" })
    assertTrue(defs.any { it.role == ScanPortRole.SCAN_IN && it.chainIndex == 0 && it.name == "scan_in_0" })
    assertTrue(defs.any { it.role == ScanPortRole.SCAN_OUT && it.chainIndex == 0 && it.name == "scan_out_0" })
    assertTrue(defs.any { it.role == ScanPortRole.SCAN_IN && it.chainIndex == 1 && it.name == "scan_in_1" })
    assertTrue(defs.any { it.role == ScanPortRole.SCAN_OUT && it.chainIndex == 1 && it.name == "scan_out_1" })
    assertTrue(defs.any { it.role == ScanPortRole.JTAG_TCK && it.name == "tck" })
    assertTrue(defs.any { it.role == ScanPortRole.MBIST_FAIL && it.name == "bist_fail" })

    val scanEnDecl = defs.first { it.role == ScanPortRole.SCAN_ENABLE }.toVerilogDeclaration()
    assertTrue("Expected input scan_en declaration", "input scan_en;" in scanEnDecl)
  }

  @Test
  fun insertScanChainsIntoVerilogNetlist_rewritesFlopsAddsPortsAndStitchesChains() {
    val res = ScanChainStitcher.insertScanChainsIntoVerilogNetlist(
      netlistVerilog = rawStructuralNetlist,
      library = TargetLibrary.NANGATE45_45NM,
      config = DftConfig(
        scanInsertionEnabled = true,
        scanStyle = DftScanStyle.MUXED_D,
        targetChainCount = 2,
        maxChainLength = 8,
        insertLockupLatches = true,
      ),
    )

    assertEquals("pipeline_reg", res.moduleName)
    assertEquals(4, res.convertedFlopCount)
    assertEquals(2, res.scanChains.size)
    assertTrue("Expected continuity check valid, got issues=${res.continuityReport.issues}", res.continuityReport.isValid)
    assertEquals(2, res.continuityReport.verifiedChains)

    val out = res.scanInsertedVerilog
    assertTrue("Expected scan_en in module ports", "scan_en" in out)
    assertTrue("Expected scan_in_0 port declaration", "input scan_in_0;" in out)
    assertTrue("Expected scan_out_0 port declaration", "output scan_out_0;" in out)
    assertTrue("Expected scan_in_1 port declaration", "input scan_in_1;" in out)
    assertTrue("Expected scan_out_1 port declaration", "output scan_out_1;" in out)
    assertTrue("Expected SDFFR_X1 cell conversion", "SDFFR_X1 u_ff0" in out)
    assertTrue("Expected SDFF_X1 cell conversion", "SDFF_X1 u_ff3" in out)
    assertTrue("Expected .SE(scan_en) wiring", ".SE(scan_en)" in out)
    assertTrue("Expected scan_in_0 stitched to first FF", ".SI(scan_in_0)" in out)
    assertTrue("Expected scan_in_1 stitched to second chain FF", ".SI(scan_in_1)" in out)
    assertTrue("Expected tail assign for scan_out_0", "assign scan_out_0 =" in out)
    assertTrue("Expected tail assign for scan_out_1", "assign scan_out_1 =" in out)
  }

  @Test
  fun verifyChainContinuity_detectsBrokenScanConnection() {
    val ports = listOf(
      RtlPort("clk", PortDirection.INPUT, 1, false),
      RtlPort("scan_en", PortDirection.INPUT, 1, false),
      RtlPort("scan_in_0", PortDirection.INPUT, 1, false),
      RtlPort("scan_out_0", PortDirection.OUTPUT, 1, false),
    )
    val nodes = listOf(
      GateNode("in_scan_in_0", "INPUT", GateType.INPUT_PORT, "scan_in_0", emptyList(), "scan_in_0", 1, 0.0, 0.0, 0.0),
      GateNode("ff0", "SDFF_X1", GateType.SDFF, "u_ff0", listOf("d0", "clk", "scan_en", "scan_in_0"), "q0", 1, 0.1, 1.0, 1.0),
      GateNode("ff1", "SDFF_X1", GateType.SDFF, "u_ff1", listOf("d1", "clk", "scan_en", "WRONG_NET"), "q1", 1, 0.1, 1.0, 1.0),
      GateNode("out_scan_out_0", "OUTPUT", GateType.OUTPUT_PORT, "scan_out_0", listOf("q1"), "scan_out_0", 1, 0.0, 0.0, 0.0),
    )
    val chains = listOf(
      DftScanChain(
        chainIndex = 0,
        scanInPort = "scan_in_0",
        scanOutPort = "scan_out_0",
        scanEnablePort = "scan_en",
        clockDomains = listOf("clk"),
        totalFlipFlopBits = 2,
        hasLockupLatch = false,
        cells = listOf(
          DftScanCell(0, "ff0", "u_ff0", "SDFF_X1", "q0", "clk", 1),
          DftScanCell(1, "ff1", "u_ff1", "SDFF_X1", "q1", "clk", 1),
        ),
      )
    )

    val continuity = ScanChainStitcher.verifyChainContinuity(ports, nodes, chains, DftConfig(scanInsertionEnabled = true))
    assertFalse("Expected continuity check to fail on broken .SI wire", continuity.isValid)
    assertTrue(continuity.issues.any { "WRONG_NET" in it })
  }

  @Test
  fun insertScanChainsIntoVerilogNetlist_handlesAnsiGroupedPortsParameterizedFlopsAndLockupLatches() {
    val ansiDualClkNetlist = """
      module dual_clk_pipe (
        input wire clk_a, clk_b, rst_n,
        input wire [1:0] din,
        output wire dout
      );
        wire n0;
        DFFR_X1 #(.INIT(1'b0)) u_stage0 ( .CLK(clk_a), .RST(rst_n), .D(din[0]), .Q(n0) );
        DFF_X1 u_stage1 ( .CLK(clk_b), .D(n0), .Q(dout) );
      endmodule
    """.trimIndent()

    val res = ScanChainStitcher.insertScanChainsIntoVerilogNetlist(
      netlistVerilog = ansiDualClkNetlist,
      library = TargetLibrary.ASAP7_7NM,
      config = DftConfig(
        scanInsertionEnabled = true,
        scanStyle = DftScanStyle.MUXED_D,
        targetChainCount = 1,
        maxChainLength = 8,
        insertLockupLatches = true,
      ),
    )

    assertEquals("dual_clk_pipe", res.moduleName)
    assertEquals(2, res.convertedFlopCount)
    assertTrue("Expected lockup latch inserted at clk_a -> clk_b boundary", res.lockupLatchCount >= 1)
    assertTrue("Expected valid continuity across lockup latch, got ${res.continuityReport.issues}", res.continuityReport.isValid)
    assertTrue("Expected ANSI scan_in_0 in module port list", "input wire scan_in_0" in res.scanInsertedVerilog)
    assertTrue("Expected ANSI scan_out_0 in module port list", "output wire scan_out_0" in res.scanInsertedVerilog)
    assertTrue("Expected lockup latch DLLATCHx1_ASAP7 in stitched netlist", "DLLATCHx1_ASAP7" in res.scanInsertedVerilog)
  }

  @Test
  fun dftEngineApplyScanInsertion_alignsLockupNamesAndAvoidsPortCollisions() {
    val ports = listOf(
      RtlPort("clk_a", PortDirection.INPUT, 1, false),
      RtlPort("clk_b", PortDirection.INPUT, 1, false),
      RtlPort("scan_in_0", PortDirection.INPUT, 1, false),
      RtlPort("d", PortDirection.INPUT, 1, false),
      RtlPort("q", PortDirection.OUTPUT, 1, false),
    )
    val nodes = listOf(
      GateNode("ff_a", "DFFx1_ASAP7", GateType.DFF, "u_a", listOf("d", "clk_a"), "n_a", 1, 0.1, 1.0, 1.0, clockDomain = "clk_a"),
      GateNode("ff_b", "DFFx1_ASAP7", GateType.DFF, "u_b", listOf("n_a", "clk_b"), "q", 1, 0.1, 1.0, 1.0, clockDomain = "clk_b"),
    )
    val warnings = mutableListOf<String>()
    val log = mutableListOf<String>()

    val outcome = DftEngine.applyScanInsertion(
      ports = ports,
      mappedNodes = nodes,
      library = TargetLibrary.ASAP7_7NM,
      config = DftConfig(scanInsertionEnabled = true, targetChainCount = 1, insertLockupLatches = true),
      warnings = warnings,
      log = log,
    )

    val chain = outcome.scanChains.single()
    val lockupCells = chain.cells.filter { it.isLockupLatch }
    assertTrue("Expected lockup cell in multi-clock chain", lockupCells.isNotEmpty())
    lockupCells.forEach { cell ->
      val matchingNode = outcome.nodes.first { it.id == cell.nodeId }
      assertEquals("Lockup latch instanceName in chain must match node instanceName", matchingNode.instanceName, cell.instanceName)
    }
    val continuity = ScanChainStitcher.verifyChainContinuity(
      ports = outcome.ports,
      nodes = outcome.nodes,
      chains = outcome.scanChains,
      config = DftConfig(scanInsertionEnabled = true, insertLockupLatches = true),
    )
    assertTrue("Expected clean continuity on DftEngine outcome, got ${continuity.issues}", continuity.isValid)
  }
}
