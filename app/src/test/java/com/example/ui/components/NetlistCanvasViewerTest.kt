package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SynthesisResult
import com.example.ui.theme.RtlSynthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetlistCanvasViewerTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun canvasViewer_supportsTouchPanPinchAndZoomButtons() {
    val report = (RtlSynthesisEngine.synthesize(
      verilogSource = PresetRtlDesigns.templates.first().verilogCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success).report

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        Box(modifier = Modifier.size(480.dp, 640.dp)) {
          NetlistCanvasViewer(
            report = report,
            selectedNodeId = report.nodes.first().id,
            onSelectNode = {},
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("netlist_canvas").assertIsDisplayed()
    composeTestRule.onNodeWithTag("netlist_zoom_pill").assertIsDisplayed()
    composeTestRule.onNodeWithTag("netlist_minimap").assertIsDisplayed()
    composeTestRule.onNodeWithTag("netlist_center_selected_button").assertIsDisplayed()

    // Perform touch pan and pinch-to-zoom gestures on the Canvas
    composeTestRule.onNodeWithTag("netlist_canvas").performTouchInput {
      swipeLeft()
      pinch(
        start0 = Offset(140f, 300f),
        end0 = Offset(80f, 300f),
        start1 = Offset(220f, 300f),
        end1 = Offset(300f, 300f),
      )
    }
    composeTestRule.waitForIdle()

    // Exercise zoom-in, zoom-out, center-selected, fit, and minimap tap
    composeTestRule.onNodeWithTag("netlist_zoom_in_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("netlist_zoom_out_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("netlist_center_selected_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("netlist_minimap").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("netlist_fit_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("netlist_canvas").assertIsDisplayed()
  }
}
