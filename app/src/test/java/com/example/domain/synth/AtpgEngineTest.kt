package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtpgEngineTest {

  private val multiChainRtl = """
    module dual_accum_scan(
      input clk,
      input rst,
      input en,
      input [3:0] din,
      output reg [3:0] sum_q,
      output reg [3:0] xor_q
    );
      always @(posedge clk or posedge rst) begin
        if (rst) begin
          sum_q <= 4'd0;
          xor_q <= 4'd0;
        end else if (en) begin
          sum_q <= sum_q + din;
          xor_q <= sum_q ^ din;
        end
      end
    endmodule
  """.trimIndent()

  @Test
  fun generatePatterns_computesPerChainScanInAndExpectedScanOutBitstreams() {
    val synth = RtlSynthesisEngine.synthesize(
      verilogSource = multiChainRtl,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
      dftConfig = DftConfig(
        scanInsertionEnabled = true,
        scanStyle = DftScanStyle.MUXED_D,
        targetChainCount = 2,
        atpgMaxPatterns = 32,
      ),
    ) as SynthesisResult.Success

    val dft = synth.report.dftReport!!
    assertTrue("Expected at least 1 scan chain", dft.scanChains.isNotEmpty())
    assertTrue("Expected compacted ATPG vectors generated", dft.atpgVectors.isNotEmpty())

    val firstVec = dft.atpgVectors.first()
    assertEquals(dft.scanChains.size, firstVec.chainVectors.size)
    firstVec.chainVectors.forEach { cv ->
      assertTrue("Expected non-empty scan_in bitstream", cv.scanInBitstream.all { it == '0' || it == '1' })
      assertTrue("Expected non-empty expected scan_out bitstream", cv.expectedScanOutBitstream.all { it == '0' || it == '1' })
      assertTrue("Expected scan_in port name", cv.scanInPort.startsWith("scan_in_"))
      assertTrue("Expected scan_out port name", cv.scanOutPort.startsWith("scan_out_"))
    }

    val totalDetectedFromVectors = dft.atpgVectors.sumOf { it.newlyDetectedFaults }
    assertEquals(dft.detectedFaults, totalDetectedFromVectors)
    assertTrue("Expected Shift statements in STIL patterns", "Shift { V {" in dft.stilPatternText)
  }

  @Test
  fun formatVectorCycleSummary_includesScanChainArrowsAndAlgorithm() {
    val vec = AtpgTestVector(
      index = 2,
      piBits = mapOf("rst" to 0, "en" to 1),
      scanInBits = mapOf("s0(q)" to 1),
      expectedPoBits = mapOf("sum_q" to 5),
      newlyDetectedFaults = 6,
      chainVectors = listOf(
        ScanChainVectorSlice(
          chainIndex = 0,
          scanInPort = "scan_in_0",
          scanOutPort = "scan_out_0",
          scanInBitstream = "10",
          expectedScanOutBitstream = "01",
        )
      ),
      targetedAlgorithm = "D-SENSITIZE",
    )

    val summary = AtpgEngine.formatVectorCycleSummary(vec)
    assertTrue(summary.contains("#2 [D-SENSITIZE +6]"))
    assertTrue(summary.contains("scan_in_0=10->scan_out_0=01"))
    assertTrue(summary.contains("PI(rst=0 en=1) -> PO(sum_q=5)"))
  }
}
