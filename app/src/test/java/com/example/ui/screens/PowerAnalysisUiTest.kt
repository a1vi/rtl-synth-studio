package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.data.model.AppSettings
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PowerAnalysisEngine
import com.example.domain.synth.TestRtlFixtures
import com.example.domain.synth.RtlSimulator
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.StimulusPattern
import com.example.domain.synth.SynthesisResult
import com.example.domain.synth.VerilogHdlParser
import com.example.domain.synth.VerilogParseResult
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.WorkbenchUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h892dp")
class PowerAnalysisUiTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun simulationScreen_displaysPowerTabAndActivityAnalysis() {
    val preset = TestRtlFixtures.counter8Bit
    val design = (VerilogHdlParser.parse(preset.verilogCode) as VerilogParseResult.Success).design
    val sim = RtlSimulator.simulate(
      design = design,
      cycleCount = 16,
      patternOverrides = mapOf("en" to StimulusPattern.HIGH, "rst" to StimulusPattern.LOW),
    )
    val synth = (RtlSynthesisEngine.synthesize(
      verilogSource = preset.verilogCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success).report
    val power = PowerAnalysisEngine.analyze(synth, sim)

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        SimulationScreen(
          simulation = sim,
          cycleCount = 16,
          stimulusPatterns = mapOf("en" to StimulusPattern.HIGH),
          onSetCycleCount = {},
          onCycleStimulusPattern = {},
          onStepCustomCellValue = { _, _, _, _ -> },
          onRunSimulation = {},
          onResetStimulus = {},
          clockPeriodNs = 2.0,
          powerAnalysis = power,
          synthesisReport = synth,
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sim_tab_power").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("sim_power_panel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_breakdown_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_switching_tile").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_internal_tile").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_leakage_tile").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_cycle_toggle_chart").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_signal_activity_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_signal_row_count").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_top_cells_card").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun reportsScreen_displaysPowerBreakdownAndJumpsToSimulationPower() {
    val preset = TestRtlFixtures.counter8Bit
    val design = (VerilogHdlParser.parse(preset.verilogCode) as VerilogParseResult.Success).design
    val sim = RtlSimulator.simulate(design, cycleCount = 16)
    val synth = (RtlSynthesisEngine.synthesize(
      verilogSource = preset.verilogCode,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 5.0,
    ) as SynthesisResult.Success).report
    val power = PowerAnalysisEngine.analyze(synth, sim)

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        ReportsScreen(
          state = WorkbenchUiState(
            designName = "counter_8bit",
            synthesisResult = SynthesisResult.Success(synth.copy(powerAnalysis = power)),
            simulationResult = sim,
            powerAnalysis = power,
            clockPeriodNs = 5.0,
          ),
          settings = AppSettings(),
          onSaveSnapshot = {},
          onSelectNode = {},
          onSetCycleCount = {},
          onCycleStimulusPattern = {},
          onStepCustomCellValue = { _, _, _, _ -> },
          onRunSimulation = {},
          onRunTestbenchSimulation = {},
          onToggleWaveformSignal = {},
          onResetStimulus = {},
          onNotify = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("reports_summary_list")
      .performScrollToNode(hasTestTag("open_power_activity_button"))
    composeTestRule.onNodeWithTag("power_breakdown_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("Switching").assertIsDisplayed()
    composeTestRule.onNodeWithText("Internal").assertIsDisplayed()
    composeTestRule.onNodeWithText("Leakage").assertIsDisplayed()

    composeTestRule.onNodeWithTag("open_power_activity_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("sim_power_panel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_cycle_toggle_chart").assertIsDisplayed()
  }
}
