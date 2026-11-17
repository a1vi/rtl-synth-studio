package com.example.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PortDirection
import com.example.domain.synth.RtlPort
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SdcParser
import com.example.domain.synth.SynthesisResult
import com.example.ui.screens.analysis.TimingAnalysisTab
import com.example.ui.theme.RtlSynthTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h892dp")
class VisualSdcEditorTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun visualSdcEditor_buildsClockFreqInputDelayAndOutputLoadConstraints() {
    var appliedSdc = ""
    val ports = listOf(
      RtlPort(name = "clk", direction = PortDirection.INPUT, width = 1, isReg = false),
      RtlPort(name = "data_in", direction = PortDirection.INPUT, width = 8, isReg = false, msb = 7, lsb = 0),
      RtlPort(name = "y_out", direction = PortDirection.OUTPUT, width = 8, isReg = false, msb = 7, lsb = 0),
    )
    val initialSdc = """
      create_clock -name clk -period 4.00 [get_ports clk]
    """.trimIndent()

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          VisualSdcEditorCard(
            sdcText = initialSdc,
            clockPeriodNs = 4.0,
            ports = ports,
            targetLibrary = TargetLibrary.SKY130_130NM,
            moduleName = "dsp_stage",
            onUpdateSdc = { appliedSdc = it },
            onResetSdc = {},
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("visual_sdc_editor_card").assertIsDisplayed()

    // 1. Change clock frequency via preset chip (500 MHz -> 2.00 ns)
    composeTestRule.onNodeWithTag("sdc_freq_chip_0_500").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    // 2. Switch to Input Delays tab and add an input delay constraint
    composeTestRule.onNodeWithTag("sdc_tab_inputs").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sdc_add_input_delay_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sdc_input_delay_row_val_0")
      .performScrollTo()
      .performTextReplacement("0.75")
    composeTestRule.waitForIdle()

    // 3. Switch to Output Loads tab, add output load, and set 1.50 pF
    composeTestRule.onNodeWithTag("sdc_tab_loads").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sdc_add_output_load_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sdc_load_chip_0_150").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Est. Buffer RC Delay", substring = true).assertIsDisplayed()

    // 4. Apply constraints and verify serialized SDC contains all 3 rules
    composeTestRule.onNodeWithTag("apply_sdc_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    val parsed = SdcParser.parse(appliedSdc, fallbackClockNs = 4.0)
    assertEquals(1, parsed.clocks.size)
    assertEquals(2.0, parsed.clocks.first().periodNs, 1e-3)
    assertEquals(1, parsed.ioDelays.count { it.isInput })
    assertEquals(0.75, parsed.inputDelayForPort("data_in"), 1e-3)
    assertEquals(1, parsed.outputLoads.size)
    assertEquals(1.50, parsed.outputLoadForPort("y_out"), 1e-3)
    assertTrue(appliedSdc.contains("set_load -pin_load 1.50"))
  }

  @Test
  fun timingAnalysisTab_expandsVisualSdcEditorAndAppliesConstraint() {
    val rtl = """
      module comb_top (
        input wire clk,
        input wire a,
        output wire y
      );
        assign y = ~a;
      endmodule
    """.trimIndent()
    val synthSuccess = RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
      sdcText = "create_clock -name clk -period 2.00 [get_ports clk]\nset_load -pin_load 0.50 [get_ports y]",
    ) as SynthesisResult.Success

    var appliedFromTab = ""

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        TimingAnalysisTab(
          report = synthSuccess.report,
          layout = null,
          activePathId = null,
          onSelectActivePathId = {},
          onInspectNode = {},
          onViewPathInSchematic = {},
          onJumpToRtlLine = {},
          sdcText = "create_clock -name clk -period 2.00 [get_ports clk]\nset_load -pin_load 0.50 [get_ports y]",
          ports = synthSuccess.report.ports,
          onUpdateSdc = { appliedFromTab = it },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("analysis_timing_list")
      .performScrollToNode(hasTestTag("timing_tab_sdc_toggle"))
    composeTestRule.onNodeWithTag("timing_tab_sdc_toggle").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("visual_sdc_editor_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("analysis_timing_list")
      .performScrollToNode(hasTestTag("apply_sdc_button"))
    composeTestRule.onNodeWithTag("apply_sdc_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(appliedFromTab.contains("set_load"))
  }
}
