package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.example.data.model.AppSettings
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.DftConfig
import com.example.domain.synth.DftScanStyle
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.computeCoverageRamp
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.WorkbenchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h892dp")
class DftUiFeaturesTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun synthesisScreen_displaysDftCardAndTogglesScanStyleAndOptions() {
    var toggledScan = false
    var updatedConfig = DftConfig()

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var state by remember {
          mutableStateOf(
            WorkbenchUiState(
              designName = "counter_8bit",
              dftConfig = DftConfig(scanInsertionEnabled = false),
            )
          )
        }
        SynthesisScreen(
          state = state,
          onSelectTopModule = {},
          onSelectEngine = {},
          onSelectLibrary = {},
          onSelectGoal = {},
          onClockPeriodChange = {},
          onUpdateSdc = {},
          onResetSdc = {},
          onRunSynthesis = {},
          onJumpToEditor = {},
          onOpenReports = {},
          onNotify = {},
          onToggleDftScan = {
            toggledScan = true
            val next = state.dftConfig.copy(scanInsertionEnabled = !state.dftConfig.scanInsertionEnabled)
            updatedConfig = next
            state = state.copy(dftConfig = next)
          },
          onUpdateDftConfig = { transform ->
            val next = transform(state.dftConfig)
            updatedConfig = next
            state = state.copy(dftConfig = next)
          },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("dft_config_card"))
    composeTestRule.onNodeWithTag("dft_config_card").assertIsDisplayed()

    composeTestRule.onNodeWithTag("dft_scan_enable_switch").performClick()
    composeTestRule.waitForIdle()
    assertTrue("Expected scan enable switch callback", toggledScan)
    assertTrue(updatedConfig.scanInsertionEnabled)

    composeTestRule.onNodeWithTag("synthesis_lazy_list").performTouchInput { swipeUp() }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("dft_style_LSSD").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(DftScanStyle.LSSD, updatedConfig.scanStyle)

    composeTestRule.onNodeWithTag("dft_jtag_chip").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertTrue(updatedConfig.insertJtagBoundaryTap)
  }

  @Test
  fun reportsScreen_switchesToDftTabRendersChainsStilPreviewAndRunsAtpg() {
    val dftCfg = DftConfig(
      scanInsertionEnabled = true,
      scanStyle = DftScanStyle.MUXED_D,
      targetChainCount = 2,
      insertLockupLatches = true,
      insertJtagBoundaryTap = true,
      atpgMaxPatterns = 32,
    )
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = PresetRtlDesigns.templates.first().verilogCode,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
      dftConfig = dftCfg,
    ) as SynthesisResult.Success

    var regeneratedCap = -1

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        ReportsScreen(
          state = WorkbenchUiState(
            designName = "counter_8bit",
            synthesisResult = result,
            dftConfig = dftCfg,
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
          onRegenerateAtpg = { regeneratedCap = it },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("reports_tab_dft").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("dft_kpi_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_coverage_gauge_canvas").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_test_coverage_value").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_coverage_tier_pill").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_breakdown_bar").assertIsDisplayed()

    composeTestRule.onNodeWithTag("dft_report_list")
      .performScrollToNode(hasTestTag("dft_coverage_curve_card"))
    composeTestRule.onNodeWithTag("dft_coverage_curve_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_coverage_curve_canvas").assertIsDisplayed()

    val ramp = computeCoverageRamp(
      vectors = result.report.dftReport!!.atpgVectors,
      totalFaults = result.report.dftReport!!.totalFaults,
      untestableFaults = result.report.dftReport!!.untestableFaults,
    )
    assertTrue("Expected non-empty ATPG coverage ramp", ramp.isNotEmpty())
    for (i in 1 until ramp.size) {
      assertTrue(
        "Coverage ramp must be non-decreasing",
        ramp[i].testCoveragePct >= ramp[i - 1].testCoveragePct,
      )
    }

    composeTestRule.onNodeWithTag("dft_report_list")
      .performScrollToNode(hasTestTag("dft_rule_checks_card"))
    composeTestRule.onNodeWithTag("dft_rule_checks_card").assertIsDisplayed()

    composeTestRule.onNodeWithTag("dft_report_list")
      .performScrollToNode(hasTestTag("dft_scan_chains_card"))
    composeTestRule.onNodeWithTag("dft_scan_chains_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_scan_ports_table").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_netlist_stitch_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_stitched_netlist_result").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("dft_report_list")
      .performScrollToNode(hasTestTag("dft_atpg_card"))
    composeTestRule.onNodeWithTag("dft_vector_inspector_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_vector_chip_0").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("dft_atpg_cap_64").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("dft_run_atpg_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(64, regeneratedCap)

    composeTestRule.onNodeWithTag("dft_report_list")
      .performScrollToNode(hasTestTag("dft_stil_export_card"))
    composeTestRule.onNodeWithTag("dft_preview_scandef_chip").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_preview_verilog_chip").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_copy_artifact_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_export_stil_button").assertIsDisplayed()
  }

  @Test
  fun reportsScreen_nonScanSequentialOffersScanBoostActionAndShowsSummaryBar() {
    val nonScanCfg = DftConfig(scanInsertionEnabled = false)
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = PresetRtlDesigns.templates.first().verilogCode,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
      dftConfig = nonScanCfg,
    ) as SynthesisResult.Success

    var boostClicked = false

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        ReportsScreen(
          state = WorkbenchUiState(
            designName = "counter_8bit",
            synthesisResult = result,
            dftConfig = nonScanCfg,
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
          onEnableScanInsertion = { boostClicked = true },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("reports_summary_list")
      .performScrollToNode(hasTestTag("reports_dft_summary_card"))
    composeTestRule.onNodeWithTag("reports_dft_coverage_bar").assertIsDisplayed()

    composeTestRule.onNodeWithTag("reports_tab_dft").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("dft_enable_scan_boost_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertTrue("Expected one-tap scan insertion CTA to fire callback", boostClicked)
  }
}
