package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlSimulator
import com.example.domain.synth.StimulusPattern
import com.example.domain.synth.VerilogHdlParser
import com.example.domain.synth.VerilogParseResult
import com.example.ui.theme.RtlSynthTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h892dp")
class SimulationScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun simulationScreen_switchesRadixStepsCyclesAndOverridesStimulus() {
    val preset = PresetRtlDesigns.templates.first { it.name == "counter_8bit" }
    val design = (VerilogHdlParser.parse(preset.verilogCode) as VerilogParseResult.Success).design

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var cycleCount by remember { mutableIntStateOf(16) }
        var patterns by remember { mutableStateOf<Map<String, StimulusPattern>>(emptyMap()) }
        var overrides by remember { mutableStateOf<Map<Pair<String, Int>, Long>>(emptyMap()) }
        val sim = remember(cycleCount, patterns, overrides) {
          RtlSimulator.simulate(design, cycleCount, patterns, overrides)
        }

        SimulationScreen(
          simulation = sim,
          cycleCount = cycleCount,
          stimulusPatterns = patterns,
          onSetCycleCount = { cycleCount = it },
          onCycleStimulusPattern = { port ->
            val next = (patterns[port] ?: StimulusPattern.AUTO).next()
            patterns = patterns + (port to next)
          },
          onStepCustomCellValue = { port, cyc, v, w ->
            overrides = overrides + ((port to cyc) to RtlSimulator.maskValue(v, w))
          },
          onRunSimulation = {},
          onResetStimulus = {
            patterns = emptyMap()
            overrides = emptyMap()
          },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("waveform_viewer_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("sim_cycle_probe_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("Cycle C0 Probe").assertIsDisplayed()

    // Step to cycle C1
    composeTestRule.onNodeWithTag("sim_next_cycle_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Cycle C1 Probe").assertIsDisplayed()

    // Switch radix to BIN and verify binary width formatting
    composeTestRule.onNodeWithTag("sim_radix_bin").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("00000000").assertIsDisplayed()

    // Change cycle depth to 8c
    composeTestRule.onNodeWithTag("sim_cycles_8").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("counter_8bit · 8 cycles · clk=clk").assertIsDisplayed()

    // Cycle stimulus pattern on 'en'
    composeTestRule.onNodeWithTag("stim_pattern_en").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("en: Count").assertIsDisplayed()

    // Tap Flip on 'en' override in Cycle C1 probe and verify value updates to 0
    composeTestRule.onNodeWithTag("sim_value_en").performScrollTo().assertTextEquals("1")
    composeTestRule.onNodeWithTag("sim_override_en").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sim_value_en").performScrollTo().assertTextEquals("0")
  }
}
