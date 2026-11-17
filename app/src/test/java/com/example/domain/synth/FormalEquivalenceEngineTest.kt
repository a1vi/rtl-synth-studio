package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormalEquivalenceEngineTest {

  @Test
  fun combinationalAsicDesign_provesExhaustiveEquivalenceAcrossAllOutputs() {
    val rtl = """
      module comb_logic (
        input  wire a,
        input  wire b,
        input  wire sel,
        output wire y_and,
        output wire y_xor,
        output wire y_mux
      );
        assign y_and = a & b;
        assign y_xor = a ^ b;
        assign y_mux = sel ? b : ~a;
      endmodule
    """.trimIndent()

    val synth = RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success

    val lec = FormalEquivalenceEngine.verify(
      goldenRtlSource = rtl,
      report = synth.report,
    )

    assertEquals(LecOverallStatus.EQUIVALENT, lec.overallStatus)
    assertEquals(3, lec.totalComparePoints)
    assertEquals(3, lec.matchedCount)
    assertEquals(3, lec.provenCount)
    assertEquals(0, lec.nonEquivalentCount)
    assertEquals(0, lec.inconclusiveCount)
    assertTrue(
      lec.comparePoints.all {
        it.verdict == ComparePointVerdict.PROVEN_EQUIVALENT &&
          it.proofMethod == VerificationProofMethod.EXACT_EXHAUSTIVE_MITER
      },
    )
  }

  @Test
  fun tamperedRevisedNetlist_detectsNonEquivalenceWithCounterexample() {
    val rtl = """
      module gate_pair (
        input  wire a,
        input  wire b,
        output wire y
      );
        assign y = a & b;
      endmodule
    """.trimIndent()

    val synth = RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success

    val tamperedNetlist = """
      module gate_pair (
        input wire a,
        input wire b,
        output wire y
      );
        OR2x1_ASAP7 u_bug (.A(a), .B(b), .Y(y));
      endmodule
    """.trimIndent()

    val lec = FormalEquivalenceEngine.verify(
      goldenRtlSource = rtl,
      report = synth.report,
      config = LecConfig(customRevisedNetlistOverride = tamperedNetlist),
    )

    assertEquals(LecOverallStatus.NON_EQUIVALENT, lec.overallStatus)
    assertEquals(1, lec.nonEquivalentCount)
    val pt = lec.comparePoints.first { it.name == "y" }
    assertEquals(ComparePointVerdict.NON_EQUIVALENT, pt.verdict)
    val cex = pt.counterexample
    assertNotNull("Expected counterexample for tampered netlist", cex)
    assertNotEquals(cex!!.goldenValueHex, cex.revisedValueHex)
    // Distinguishing input for (a & b) vs (a | b) must have a != b
    assertNotEquals(cex.stimulusInputs["a"], cex.stimulusInputs["b"])
  }

  @Test
  fun modifiedGoldenRtlAgainstPriorNetlist_detectsDivergenceAndFlagsStale() {
    val originalRtl = """
      module comb_fn (
        input  wire a,
        input  wire b,
        output wire y
      );
        assign y = a & b;
      endmodule
    """.trimIndent()

    val synth = RtlSynthesisEngine.synthesize(
      verilogSource = originalRtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success

    val editedGoldenRtl = """
      module comb_fn (
        input  wire a,
        input  wire b,
        output wire y
      );
        assign y = a | b;
      endmodule
    """.trimIndent()

    val lec = FormalEquivalenceEngine.verify(
      goldenRtlSource = editedGoldenRtl,
      report = synth.report,
      isNetlistStale = true,
    )

    assertTrue(lec.isNetlistStale)
    assertEquals(LecOverallStatus.NON_EQUIVALENT, lec.overallStatus)
    assertNotNull(lec.comparePoints.first { it.name == "y" }.counterexample)
  }

  @Test
  fun sequentialDesignWithDftScanConstraint_verifiesBoundedEquivalenceOnlyWhenScanDisabled() {
    val seqRtl = """
      module sync_en_acc (
        input  wire       clk,
        input  wire       rst,
        input  wire       en,
        input  wire [3:0] d,
        output reg  [3:0] q
      );
        always @(posedge clk) begin
          if (rst) begin
            q <= 4'd0;
          end else if (en) begin
            q <= q + d;
          end
        end
      endmodule
    """.trimIndent()

    val synth = RtlSynthesisEngine.synthesize(
      verilogSource = seqRtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.5,
      dftConfig = DftConfig(
        scanInsertionEnabled = true,
        scanStyle = DftScanStyle.MUXED_D,
        targetChainCount = 1,
        atpgMaxPatterns = 16,
      ),
    ) as SynthesisResult.Success

    val lecConstrained = FormalEquivalenceEngine.verify(
      goldenRtlSource = seqRtl,
      report = synth.report,
      config = LecConfig(constrainScanEnableZero = true, bmcCycleDepth = 16),
    )

    assertEquals(LecOverallStatus.EQUIVALENT, lecConstrained.overallStatus)
    assertTrue(lecConstrained.dftScanConstrained)
    assertTrue(lecConstrained.boundedEquivalentCount >= 1)
    assertEquals(0, lecConstrained.nonEquivalentCount)

    val lecUnconstrained = FormalEquivalenceEngine.verify(
      goldenRtlSource = seqRtl,
      report = synth.report,
      config = LecConfig(constrainScanEnableZero = false, bmcCycleDepth = 16),
    )

    assertNotEquals(
      "Unconstrained scan-enabled netlist must not pass functional equivalence without SE=0",
      LecOverallStatus.EQUIVALENT,
      lecUnconstrained.overallStatus,
    )
  }

  @Test
  fun fpgaLutNetlistAndComparatorCells_honestlyReportInconclusive() {
    val rtl = """
      module cmp_lut (
        input  wire [3:0] a,
        input  wire [3:0] b,
        output wire       gt
      );
        assign gt = (a > b);
      endmodule
    """.trimIndent()

    val fpgaSynth = RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.ARTIX7_FPGA,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 5.0,
    ) as SynthesisResult.Success

    val fpgaLec = FormalEquivalenceEngine.verify(
      goldenRtlSource = rtl,
      report = fpgaSynth.report,
    )

    assertEquals(LecOverallStatus.INCONCLUSIVE, fpgaLec.overallStatus)
    assertTrue(fpgaLec.inconclusiveCount > 0)
    assertTrue(fpgaLec.abstractionReasons.isNotEmpty())

    val asicSynth = RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success

    val cmpLec = FormalEquivalenceEngine.verify(
      goldenRtlSource = rtl,
      report = asicSynth.report,
    )

    assertEquals(LecOverallStatus.INCONCLUSIVE, cmpLec.overallStatus)
    assertTrue(
      cmpLec.abstractionReasons.any {
        "Comparator" in it || "Relational" in it || "CMP" in it
      },
    )
  }
}
