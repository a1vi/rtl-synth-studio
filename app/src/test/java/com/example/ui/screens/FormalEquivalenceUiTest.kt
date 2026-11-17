package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasTestTag
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.FormalEquivalenceEngine
import com.example.domain.synth.LecConfig
import com.example.domain.synth.LecOverallStatus
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SynthesisResult
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
class FormalEquivalenceUiTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun analysisScreen_lecTabDisplaysProofAndDetectsCustomNetlistBugWithLocateAction() {
    val rtl = """
      module comb_check (
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

    val initialLec = FormalEquivalenceEngine.verify(
      goldenRtlSource = rtl,
      report = synth.report,
    )
    assertEquals(LecOverallStatus.EQUIVALENT, initialLec.overallStatus)

    val buggyNetlist = """
      module comb_check (
        input wire a,
        input wire b,
        output wire y
      );
        OR2x1_ASAP7 u_bug (.A(a), .B(b), .Y(y));
      endmodule
    """.trimIndent()

    var latestLecStatus: LecOverallStatus = initialLec.overallStatus
    var locatedNodeIds: Set<String>? = null

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var lecCfg by remember { mutableStateOf(LecConfig()) }
        var lecRep by remember { mutableStateOf(initialLec) }
        AnalysisScreen(
          state = WorkbenchUiState(
            designName = "comb_check",
            verilogCode = rtl,
            synthesisResult = synth,
            lecReport = lecRep,
            lecConfig = lecCfg,
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
          onLocateNodesInSchematic = { ids -> locatedNodeIds = ids },
          onUpdateLecConfig = { transform -> lecCfg = transform(lecCfg) },
          onRunLec = { overrideText ->
            val nextCfg = lecCfg.copy(customRevisedNetlistOverride = overrideText?.takeIf { it.isNotBlank() })
            lecCfg = nextCfg
            lecRep = FormalEquivalenceEngine.verify(
              goldenRtlSource = rtl,
              report = synth.report,
              config = nextCfg,
            )
            latestLecStatus = lecRep.overallStatus
          },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("analysis_tab_lec").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("lec_control_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("lec_status_pill").assertIsDisplayed()
    composeTestRule.onNodeWithTag("lec_summary_card").assertIsDisplayed()

    // Select BMC depth chip and inject tampered custom netlist
    composeTestRule.onNodeWithTag("lec_bmc_depth_32").performClick()
    composeTestRule.onNodeWithTag("lec_custom_netlist_toggle").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lec_custom_netlist_input").performTextReplacement(buggyNetlist)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lec_run_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(LecOverallStatus.NON_EQUIVALENT, latestLecStatus)

    composeTestRule.onNodeWithTag("analysis_lec_list")
      .performScrollToNode(hasTestTag("lec_point_y"))
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lec_counterexample_y").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("lec_locate_y").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertNotNull(locatedNodeIds)
    assertTrue(locatedNodeIds!!.isNotEmpty())

    composeTestRule.onNodeWithTag("analysis_lec_list")
      .performScrollToNode(hasTestTag("lec_log_card"))
    composeTestRule.onNodeWithTag("lec_log_card").assertIsDisplayed()
  }

  @Test
  fun synthesisScreen_displaysLecSummaryAndOpensFormalLecTab() {
    val rtl = """
      module synth_lec_check (
        input  wire a,
        input  wire b,
        output wire y
      );
        assign y = a ^ b;
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

    var openedLec = false

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        SynthesisScreen(
          state = WorkbenchUiState(
            designName = "synth_lec_check",
            verilogCode = rtl,
            synthesisResult = synth,
            lecReport = lec,
          ),
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
          onOpenFormalLec = { openedLec = true },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("synth_lec_summary_strip"))
    composeTestRule.onNodeWithTag("synth_lec_summary_strip").assertIsDisplayed()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("synth_open_lec_button"))
    composeTestRule.onNodeWithTag("synth_open_lec_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue("Expected Formal LEC button to trigger navigation", openedLec)
  }
}
