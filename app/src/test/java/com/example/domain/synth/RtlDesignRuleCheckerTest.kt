package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtlDesignRuleCheckerTest {

  @Test
  fun presetTemplates_passWithZeroErrorsAndZeroWarnings() {
    PresetRtlDesigns.templates.forEach { template ->
      val outcome = RtlDesignRuleChecker.checkSource(template.verilogCode)
      assertTrue(
        "Expected Ready outcome for preset ${template.name}, got $outcome",
        outcome is RtlDrcScanOutcome.Ready,
      )
      val report = (outcome as RtlDrcScanOutcome.Ready).report
      assertEquals("Preset ${template.name} should have 0 DRC errors: ${report.violations}", 0, report.errorCount)
      assertEquals("Preset ${template.name} should have 0 DRC warnings: ${report.violations}", 0, report.warningCount)
      assertTrue("Preset ${template.name} should report isClean", report.isClean)
    }
  }

  @Test
  fun drcRst01_detectsMissingResetInSequentialAlwaysBlock() {
    val rtl = """
      module no_rst_cnt(
        input wire clk,
        input wire en,
        output reg [3:0] q
      );
        always @(posedge clk) begin
          if (en) q <= q + 4'd1;
        end
      endmodule
    """.trimIndent()

    val report = (RtlDesignRuleChecker.checkSource(rtl) as RtlDrcScanOutcome.Ready).report
    val rst01 = report.violations.filter { it.ruleId == "DRC-RST-01" }
    assertTrue("Expected DRC-RST-01 violation, got ${report.violations}", rst01.isNotEmpty())
    assertEquals(RtlDrcSeverity.WARNING, rst01.first().severity)
    assertTrue(rst01.first().signalOrBlock.contains("q"))
  }

  @Test
  fun drcRst02_detectsIncompleteResetInitialization() {
    val rtl = """
      module partial_rst(
        input wire clk,
        input wire rst,
        input wire [3:0] d,
        output reg [3:0] q1,
        output reg [3:0] q2
      );
        always @(posedge clk or posedge rst) begin
          if (rst) begin
            q1 <= 4'd0;
          end else begin
            q1 <= d;
            q2 <= q1;
          end
        end
      endmodule
    """.trimIndent()

    val report = (RtlDesignRuleChecker.checkSource(rtl) as RtlDrcScanOutcome.Ready).report
    val rst02 = report.violations.filter { it.ruleId == "DRC-RST-02" }
    assertTrue("Expected DRC-RST-02 for unreset q2, got ${report.violations}", rst02.isNotEmpty())
    assertTrue(rst02.any { it.signalOrBlock.contains("q2") })
  }

  @Test
  fun drcRst03_detectsBlockingAssignmentInSequentialBlock() {
    val rtl = """
      module blocking_seq(
        input wire clk,
        input wire rst,
        input wire d,
        output reg q
      );
        always @(posedge clk or posedge rst) begin
          if (rst) q = 1'b0;
          else q = d;
        end
      endmodule
    """.trimIndent()

    val report = (RtlDesignRuleChecker.checkSource(rtl) as RtlDrcScanOutcome.Ready).report
    val rst03 = report.violations.filter { it.ruleId == "DRC-RST-03" }
    assertTrue("Expected DRC-RST-03 for blocking '=' in clocked block", rst03.isNotEmpty())
  }

  @Test
  fun drcLatch01_detectsIncompleteIfAndIncompleteCaseWithoutFalsePositivesOnDefaults() {
    val buggyIf = """
      module latch_if(input wire en, input wire a, output reg y);
        always @(*) begin
          if (en) y = a;
        end
      endmodule
    """.trimIndent()
    val buggyIfReport = (RtlDesignRuleChecker.checkSource(buggyIf) as RtlDrcScanOutcome.Ready).report
    assertTrue(
      "Expected DRC-LATCH-01 for incomplete if, got ${buggyIfReport.violations}",
      buggyIfReport.violations.any { it.ruleId == "DRC-LATCH-01" && it.signalOrBlock == "y" },
    )

    val buggyCase = """
      module latch_case(input wire [1:0] sel, input wire a, input wire b, output reg y);
        always @(*) begin
          case (sel)
            2'b00: y = a;
            2'b01: y = b;
          endcase
        end
      endmodule
    """.trimIndent()
    val buggyCaseReport = (RtlDesignRuleChecker.checkSource(buggyCase) as RtlDrcScanOutcome.Ready).report
    assertTrue(
      "Expected DRC-LATCH-01 for incomplete case without default",
      buggyCaseReport.violations.any { it.ruleId == "DRC-LATCH-01" && it.signalOrBlock == "y" },
    )

    val cleanDefault = """
      module comb_default(input wire en, input wire a, output reg y);
        always @(*) begin
          y = 1'b0;
          if (en) y = a;
        end
      endmodule
    """.trimIndent()
    val cleanReport = (RtlDesignRuleChecker.checkSource(cleanDefault) as RtlDrcScanOutcome.Ready).report
    assertFalse(
      "Pre-assigned default before if should not trigger DRC-LATCH-01: ${cleanReport.violations}",
      cleanReport.violations.any { it.ruleId == "DRC-LATCH-01" },
    )
  }

  @Test
  fun drcLatch02And03_detectsNonBlockingInCombAndExplicitAlwaysLatch() {
    val nonBlockingComb = """
      module nb_comb(input wire a, input wire b, output reg y);
        always_comb begin
          y <= a & b;
        end
      endmodule
    """.trimIndent()
    val nbReport = (RtlDesignRuleChecker.checkSource(nonBlockingComb) as RtlDrcScanOutcome.Ready).report
    assertTrue(nbReport.violations.any { it.ruleId == "DRC-LATCH-02" })

    val explicitLatch = """
      module exp_latch(input wire en, input wire d, output logic q);
        always_latch begin
          if (en) q = d;
        end
      endmodule
    """.trimIndent()
    val latchReport = (RtlDesignRuleChecker.checkSource(explicitLatch) as RtlDrcScanOutcome.Ready).report
    assertTrue(latchReport.violations.any { it.ruleId == "DRC-LATCH-03" && it.severity == RtlDrcSeverity.INFO })
  }

  @Test
  fun drcLoop01_detectsCombinationalFeedbackLoopsAcrossAssignsAndAlwaysComb() {
    val assignLoop = """
      module comb_loop(input wire en, output wire y);
        wire n1, n2;
        assign n1 = n2 & en;
        assign n2 = ~n1;
        assign y = n2;
      endmodule
    """.trimIndent()
    val loopReport = (RtlDesignRuleChecker.checkSource(assignLoop) as RtlDrcScanOutcome.Ready).report
    val loops = loopReport.violations.filter { it.ruleId == "DRC-LOOP-01" }
    assertTrue("Expected DRC-LOOP-01 error for n1 <-> n2 cycle", loops.isNotEmpty())
    assertEquals(RtlDrcSeverity.ERROR, loops.first().severity)
    assertTrue(loopReport.hasBlockingErrors)

    val sequentialFeedbackClean = """
      module seq_feedback(input wire clk, input wire rst, input wire en, output reg q);
        wire next_q;
        assign next_q = en ? ~q : q;
        always @(posedge clk or posedge rst) begin
          if (rst) q <= 1'b0;
          else q <= next_q;
        end
      endmodule
    """.trimIndent()
    val cleanReport = (RtlDesignRuleChecker.checkSource(sequentialFeedbackClean) as RtlDrcScanOutcome.Ready).report
    assertFalse(
      "Flip-flop breaks feedback loop and must not trigger DRC-LOOP-01: ${cleanReport.violations}",
      cleanReport.violations.any { it.ruleId == "DRC-LOOP-01" },
    )
  }

  @Test
  fun drcLoop01_doesNotFalsePositiveOnDisjointBitSlices() {
    val sliceChain = """
      module slice_shift(input wire in0, output wire [1:0] bus);
        assign bus[0] = in0;
        assign bus[1] = ~bus[0];
      endmodule
    """.trimIndent()
    val report = (RtlDesignRuleChecker.checkSource(sliceChain) as RtlDrcScanOutcome.Ready).report
    assertFalse(
      "Disjoint bit slices bus[0] -> bus[1] must not report combinational loop: ${report.violations}",
      report.violations.any { it.ruleId == "DRC-LOOP-01" },
    )
    assertFalse(
      "Disjoint bit slices must not report multi-driver: ${report.violations}",
      report.violations.any { it.ruleId == "DRC-CONN-01" },
    )
  }

  @Test
  fun drcSensAndConnRules_detectIncompleteSensitivityMultiDriverAndUndrivenOutput() {
    val rtl = """
      module bad_conn(
        input wire a,
        input wire b,
        output reg y,
        output wire dup,
        output wire floating_out
      );
        always @(a) begin
          y = a ^ b;
        end
        assign dup = a;
        assign dup = b;
      endmodule
    """.trimIndent()

    val report = (RtlDesignRuleChecker.checkSource(rtl) as RtlDrcScanOutcome.Ready).report
    assertTrue("Expected DRC-SENS-01 for missing 'b'", report.violations.any { it.ruleId == "DRC-SENS-01" })
    assertTrue("Expected DRC-CONN-01 for multi-driven 'dup'", report.violations.any { it.ruleId == "DRC-CONN-01" && it.severity == RtlDrcSeverity.ERROR })
    assertTrue("Expected DRC-CONN-02 for undriven 'floating_out'", report.violations.any { it.ruleId == "DRC-CONN-02" })
  }

  @Test
  fun synthesisEngine_runsPreSynthesisDrcAndAttachesReport() {
    val rtl = """
      module no_rst_design(input wire clk, input wire d, output reg q);
        always @(posedge clk) begin
          q <= d;
        end
      endmodule
    """.trimIndent()

    val result = RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    )
    assertTrue(result is SynthesisResult.Success)
    val report = (result as SynthesisResult.Success).report
    assertNotNull(report.rtlDrcReport)
    assertTrue(report.rtlDrcReport!!.violations.any { it.ruleId == "DRC-RST-01" })
    assertTrue(report.synthesisLog.any { "[DRC]" in it })
    assertTrue(report.warnings.any { "DRC-RST-01" in it })
  }
}
