package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.GateNode
import com.example.domain.synth.GateType
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.StaRowFilter
import com.example.ui.components.StaSortColumn
import com.example.ui.components.StaSortDirection
import com.example.ui.components.StaTableSorter
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
class TimingPpaScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun staTableSorter_sortsBySlackAndDelayInBothDirectionsAndFilters() {
    val nodes = listOf(
      GateNode(
        id = "n_fast",
        cellName = "INV_X1",
        gateType = GateType.INV,
        instanceName = "u_inv",
        inputs = listOf("a"),
        outputNet = "w1",
        bitWidth = 1,
        delayNs = 0.05,
        areaUm2 = 1.0,
        powerUw = 1.0,
        level = 1,
        arrivalNs = 0.05,
        requiredNs = 2.0,
        slackNs = 1.95,
        isCriticalPath = false,
      ),
      GateNode(
        id = "n_viol",
        cellName = "ADD_X2",
        gateType = GateType.ADD_CELL,
        instanceName = "u_add",
        inputs = listOf("a", "b"),
        outputNet = "sum",
        bitWidth = 4,
        delayNs = 1.85,
        areaUm2 = 12.0,
        powerUw = 18.0,
        level = 2,
        arrivalNs = 2.20,
        requiredNs = 2.0,
        slackNs = -0.20,
        isCriticalPath = true,
      ),
      GateNode(
        id = "n_mid",
        cellName = "AND2_X1",
        gateType = GateType.AND2,
        instanceName = "u_and",
        inputs = listOf("a", "b"),
        outputNet = "w2",
        bitWidth = 1,
        delayNs = 0.42,
        areaUm2 = 2.5,
        powerUw = 3.2,
        level = 1,
        arrivalNs = 0.42,
        requiredNs = 2.0,
        slackNs = 1.58,
        isCriticalPath = false,
      ),
    )

    // Slack ascending places violating/tightest slack first
    val slackAsc = StaTableSorter.sortAndFilter(nodes, StaSortColumn.SLACK, StaSortDirection.ASCENDING, StaRowFilter.ALL)
    assertEquals(listOf("n_viol", "n_mid", "n_fast"), slackAsc.map { it.id })

    // Slack descending places highest margin first
    val slackDesc = StaTableSorter.sortAndFilter(nodes, StaSortColumn.SLACK, StaSortDirection.DESCENDING, StaRowFilter.ALL)
    assertEquals(listOf("n_fast", "n_mid", "n_viol"), slackDesc.map { it.id })

    // Delay descending places slowest gate first
    val delayDesc = StaTableSorter.sortAndFilter(nodes, StaSortColumn.DELAY, StaSortDirection.DESCENDING, StaRowFilter.ALL)
    assertEquals(listOf("n_viol", "n_mid", "n_fast"), delayDesc.map { it.id })

    // Delay ascending places fastest gate first
    val delayAsc = StaTableSorter.sortAndFilter(nodes, StaSortColumn.DELAY, StaSortDirection.ASCENDING, StaRowFilter.ALL)
    assertEquals(listOf("n_fast", "n_mid", "n_viol"), delayAsc.map { it.id })

    // Filters narrow to violating or critical paths
    val violations = StaTableSorter.sortAndFilter(nodes, StaSortColumn.SLACK, StaSortDirection.ASCENDING, StaRowFilter.VIOLATING)
    assertEquals(listOf("n_viol"), violations.map { it.id })

    val criticalOnly = StaTableSorter.sortAndFilter(nodes, StaSortColumn.SLACK, StaSortDirection.ASCENDING, StaRowFilter.CRITICAL_PATH)
    assertEquals(listOf("n_viol"), criticalOnly.map { it.id })
  }

  @Test
  fun timingPpaScreen_switchesToStaTableAndSortsBySlackAndDelay() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = PresetRtlDesigns.templates.first().verilogCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success

    var inspectedNodeId: String? = null

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        TimingPpaScreen(
          synthesisResult = result,
          isSaving = false,
          onSaveSnapshot = {},
          onInspectNodeInSchematic = { inspectedNodeId = it },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("timing_mode_overview").assertIsDisplayed()
    composeTestRule.onNodeWithTag("timing_mode_table").assertIsDisplayed()

    // Switch to the dedicated STA Table view
    composeTestRule.onNodeWithTag("timing_mode_table").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("sta_timing_table").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sort: Slack ↑").assertIsDisplayed()

    // Toggle Slack sort direction to descending
    composeTestRule.onNodeWithTag("sta_sort_slack").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Sort: Slack ↓").assertIsDisplayed()

    // Sort by Delay (defaults to descending slowest-first, then toggles ascending)
    composeTestRule.onNodeWithTag("sta_sort_delay").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Sort: Delay ↓").assertIsDisplayed()

    composeTestRule.onNodeWithTag("sta_sort_delay").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Sort: Delay ↑").assertIsDisplayed()

    // Filter to critical path and tap first node row to inspect in schematic
    composeTestRule.onNodeWithTag("sta_filter_critical").performClick()
    composeTestRule.waitForIdle()

    val firstNodeId = result.report.nodes.first { it.isCriticalPath }.id
    composeTestRule.onNodeWithTag("sta_row_$firstNodeId").performClick()
    composeTestRule.waitForIdle()
    assertTrue(inspectedNodeId == firstNodeId)
  }
}
