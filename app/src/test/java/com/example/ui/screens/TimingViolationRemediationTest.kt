package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.data.model.OptimizationGoal
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.StaticTimingAnalyzer
import com.example.domain.synth.SynthesisResult
import com.example.domain.synth.TestRtlFixtures
import com.example.domain.synth.TimingRemediationKind
import com.example.ui.screens.analysis.TimingAnalysisTab
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.WorkbenchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h892dp")
class TimingViolationRemediationTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun buildViolationRemediation_returnsNullWhenMet_andActionPlanWhenViolated() {
    val rtl = PresetRtlDesigns.templates.first().verilogCode

    val metResult = RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 20.0,
    ) as SynthesisResult.Success
    assertTrue("Expected MET slack at 20ns", metResult.report.wnsNs >= 0.0)
    assertNull(
      StaticTimingAnalyzer.buildViolationRemediation(metResult.report),
    )

    val violatedResult = RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 0.5,
    ) as SynthesisResult.Success
    assertTrue("Expected VIOLATED slack at 0.5ns on SKY130", violatedResult.report.wnsNs < 0.0)

    val staSummary = StaticTimingAnalyzer.analyze(violatedResult.report, null)
    val plan = StaticTimingAnalyzer.buildViolationRemediation(violatedResult.report, staSummary)
    assertNotNull(plan)
    plan!!
    assertTrue(plan.setupViolating)
    assertTrue(plan.recommendedClockNs > violatedResult.report.clockPeriodNs)
    val kinds = plan.recommendations.map { it.kind }.toSet()
    assertTrue(TimingRemediationKind.OPTIMIZE_FOR_SPEED in kinds)
    assertTrue(TimingRemediationKind.SWITCH_TIMING_ENGINE in kinds)
    assertTrue(TimingRemediationKind.PIPELINE_BOTTLENECK_RTL in kinds)
    assertTrue(TimingRemediationKind.RELAX_CLOCK_PERIOD in kinds)
    assertTrue(TimingRemediationKind.EVALUATE_FASTER_PDK in kinds)
  }

  @Test
  fun timingAnalysisTab_rendersRemediationCardOnlyOnViolationAndInvokesActions() {
    val violatedResult = RtlSynthesisEngine.synthesize(
      verilogSource = TestRtlFixtures.alu8BitSv.verilogCode,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 0.5,
    ) as SynthesisResult.Success
    assertTrue(violatedResult.report.wnsNs < 0.0)

    var appliedGoal: OptimizationGoal? = null
    var appliedClockNs: Double? = null
    var tracedPathId: String? = null

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        TimingAnalysisTab(
          report = violatedResult.report,
          layout = null,
          activePathId = null,
          onSelectActivePathId = {},
          onInspectNode = {},
          onViewPathInSchematic = { tracedPathId = it.pathId },
          onJumpToRtlLine = {},
          onApplyClockPeriod = { appliedClockNs = it },
          onApplyOptimizationGoal = { appliedGoal = it },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("timing_violation_remediation_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("timing_violation_diagnosis_box").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("remediation_action_optimize_for_speed")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    assertEquals(OptimizationGoal.MIN_DELAY, appliedGoal)

    composeTestRule.onNodeWithTag("remediation_action_relax_clock_period")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    assertNotNull(appliedClockNs)
    assertTrue(appliedClockNs!! > 0.5)

    composeTestRule.onNodeWithTag("remediation_trace_path_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    assertNotNull(tracedPathId)
  }

  @Test
  fun timingAnalysisTab_hidesRemediationCardWhenTimingMet() {
    val metResult = RtlSynthesisEngine.synthesize(
      verilogSource = TestRtlFixtures.counter8Bit.verilogCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 20.0,
    ) as SynthesisResult.Success
    assertTrue(metResult.report.wnsNs >= 0.0)

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        TimingAnalysisTab(
          report = metResult.report,
          layout = null,
          activePathId = null,
          onSelectActivePathId = {},
          onInspectNode = {},
          onViewPathInSchematic = {},
          onJumpToRtlLine = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("timing_violation_remediation_card").assertDoesNotExist()
  }

  @Test
  fun synthesisScreen_showsTimingViolationCalloutWithQuickFixes() {
    val violatedResult = RtlSynthesisEngine.synthesize(
      verilogSource = TestRtlFixtures.alu8BitSv.verilogCode,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 0.5,
    ) as SynthesisResult.Success
    assertTrue(violatedResult.report.wnsNs < 0.0)

    var selectedGoal: OptimizationGoal? = null
    var openedAnalysis = false

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        SynthesisScreen(
          state = WorkbenchUiState(
            designName = "alu_8bit_sv",
            verilogCode = TestRtlFixtures.alu8BitSv.verilogCode,
            targetLibrary = TargetLibrary.SKY130_130NM,
            optimizationGoal = OptimizationGoal.BALANCED,
            synthesisEngine = SynthesisEngineProfile.SILICON_FLOW,
            clockPeriodNs = 0.5,
            synthesisResult = violatedResult,
          ),
          onSelectTopModule = {},
          onSelectEngine = {},
          onSelectLibrary = {},
          onSelectGoal = { selectedGoal = it },
          onClockPeriodChange = {},
          onUpdateSdc = {},
          onResetSdc = {},
          onRunSynthesis = {},
          onJumpToEditor = {},
          onOpenReports = {},
          onNotify = {},
          onOpenAnalysis = { openedAnalysis = true },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("synth_timing_violation_callout"))
    composeTestRule.onNodeWithTag("synth_timing_violation_callout").assertIsDisplayed()

    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("callout_fix_min_delay"))
    composeTestRule.onNodeWithTag("callout_fix_min_delay").performClick()
    assertEquals(OptimizationGoal.MIN_DELAY, selectedGoal)

    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("callout_open_sta_guide"))
    composeTestRule.onNodeWithTag("callout_open_sta_guide").performClick()
    assertTrue(openedAnalysis)
  }
}
