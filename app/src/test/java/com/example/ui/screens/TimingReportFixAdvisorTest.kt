package com.example.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.ParsedViolationType
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.StaticTimingAnalyzer
import com.example.domain.synth.SynthesisResult
import com.example.domain.synth.TestRtlFixtures
import com.example.domain.synth.TimingReportParser
import com.example.domain.synth.VerilogFixCategory
import com.example.ui.components.TimingReportFixAdvisorCard
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.WorkbenchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h892dp")
class TimingReportFixAdvisorTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun parser_generatesSetupPipelineAndRestructuringVerilog_fromOpenStaSetupReport() {
    val parsed = TimingReportParser.parseAndSuggestFixes(TimingReportParser.SAMPLE_SETUP_REPORT)
    assertEquals("OpenSTA / PrimeTime / Tempus", parsed.detectedFormat)
    assertEquals(1, parsed.violatingPaths.size)
    assertEquals(ParsedViolationType.SETUP, parsed.violatingPaths.first().violationType)
    assertNotNull(parsed.worstSetupSlackNs)
    assertTrue(parsed.worstSetupSlackNs!! < 0.0)

    val categories = parsed.suggestions.map { it.category }.toSet()
    assertTrue(VerilogFixCategory.PIPELINE_INSERTION in categories)
    assertTrue(VerilogFixCategory.LOGIC_RESTRUCTURING in categories)

    val pipeFix = parsed.suggestions.first { it.category == VerilogFixCategory.PIPELINE_INSERTION }
    assertTrue(pipeFix.verilogFixSnippet.contains("always @(posedge clk"))
    assertTrue(pipeFix.verilogFixSnippet.contains("ex_mem_alu_res_q"))
  }

  @Test
  fun parser_generatesHoldBufferAndClockEnableVerilog_fromHoldViolationReport() {
    val parsed = TimingReportParser.parseAndSuggestFixes(TimingReportParser.SAMPLE_HOLD_REPORT)
    assertEquals(1, parsed.violatingPaths.size)
    assertEquals(ParsedViolationType.HOLD, parsed.violatingPaths.first().violationType)
    assertNotNull(parsed.worstHoldSlackNs)
    assertTrue(parsed.worstHoldSlackNs!! < 0.0)

    val categories = parsed.suggestions.map { it.category }.toSet()
    assertTrue(VerilogFixCategory.HOLD_BUFFER_INSERTION in categories)
    assertTrue(VerilogFixCategory.CLOCK_ENABLE_ALIGNMENT in categories)

    val bufFix = parsed.suggestions.first { it.category == VerilogFixCategory.HOLD_BUFFER_INSERTION }
    assertTrue(bufFix.verilogFixSnippet.contains("dont_touch"))
    assertTrue(bufFix.verilogFixSnippet.contains("ch0_shadow_q"))
  }

  @Test
  fun parser_handlesMetReportWithoutFakeViolations_andFormatsActiveSynthesisReport() {
    val metText = TimingReportParser.SAMPLE_SETUP_REPORT
      .replace("slack (VIOLATED)                                   -0.545", "slack (MET)                                         0.250")
    val metParsed = TimingReportParser.parseAndSuggestFixes(metText)
    assertEquals(1, metParsed.paths.size)
    assertTrue(metParsed.violatingPaths.isEmpty())
    assertTrue(metParsed.suggestions.isEmpty())
    assertNotNull(metParsed.parseNotice)

    val synth = RtlSynthesisEngine.synthesize(
      verilogSource = TestRtlFixtures.alu8BitSv.verilogCode,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 0.5,
    ) as SynthesisResult.Success
    val sta = StaticTimingAnalyzer.analyze(synth.report, null)
    val formatted = TimingReportParser.formatFromSynthesis(synth.report, sta)
    val parsedSynth = TimingReportParser.parseAndSuggestFixes(formatted, TestRtlFixtures.alu8BitSv.verilogCode)
    assertTrue(parsedSynth.violatingPaths.isNotEmpty())
    assertTrue(parsedSynth.suggestions.isNotEmpty())
  }

  @Test
  fun timingReportFixAdvisorCard_parsesSetupAndHoldSamplesAndCopiesVerilogFix() {
    var notifiedMsg: String? = null
    var jumpedLine = -1

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          TimingReportFixAdvisorCard(
            report = null,
            summary = null,
            layout = null,
            activeRtlSource = TestRtlFixtures.alu8BitSv.verilogCode,
            onJumpToRtlLine = { jumpedLine = it },
            onNotify = { notifiedMsg = it },
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("timing_report_fix_advisor_card").assertIsDisplayed()

    composeTestRule.onNodeWithTag("timing_parser_sample_setup").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("timing_parser_summary_strip").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("verilog_fix_card_0").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("verilog_fix_code_0").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("verilog_fix_toggle_before_0").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("verilog_fix_copy_0").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertTrue(notifiedMsg?.contains("Copied") == true)

    // Switch to Hold Violation report and verify Hold suggestions appear
    composeTestRule.onNodeWithTag("timing_parser_sample_hold").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("fix_filter_hold").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("verilog_fix_card_0").performScrollTo().assertIsDisplayed()
    assertTrue(jumpedLine == -1)
  }

  @Test
  fun analysisScreen_allowsPastingExternalTimingReportBeforeSynthesis() {
    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        AnalysisScreen(
          state = WorkbenchUiState(
            designName = "alu_8bit_sv",
            verilogCode = TestRtlFixtures.alu8BitSv.verilogCode,
            synthesisResult = null,
          ),
          onSelectNode = {},
          onOpenSchematic = {},
          onOpenSynthesis = {},
          onJumpToRtlLine = {},
          onSetCycleCount = {},
          onCycleStimulusPattern = {},
          onStepCustomCellValue = { _, _, _, _ -> },
          onRunSimulation = {},
          onRunTestbenchSimulation = {},
          onToggleWaveformSignal = {},
          onResetStimulus = {},
          onRegenerateAtpg = {},
          onEnableScanInsertion = {},
          onNotify = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("timing_report_fix_advisor_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("timing_report_parser_input")
      .performScrollTo()
      .performTextReplacement(TimingReportParser.SAMPLE_SETUP_REPORT)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("verilog_fix_card_0").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("1 VIOLATED").performScrollTo().assertIsDisplayed()
  }
}
