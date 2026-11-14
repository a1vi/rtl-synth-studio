package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.data.model.AppSettings
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
class ReportsScreenTest {

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

    val slackAsc = StaTableSorter.sortAndFilter(nodes, StaSortColumn.SLACK, StaSortDirection.ASCENDING, StaRowFilter.ALL)
    assertEquals(listOf("n_viol", "n_mid", "n_fast"), slackAsc.map { it.id })

    val slackDesc = StaTableSorter.sortAndFilter(nodes, StaSortColumn.SLACK, StaSortDirection.DESCENDING, StaRowFilter.ALL)
    assertEquals(listOf("n_fast", "n_mid", "n_viol"), slackDesc.map { it.id })

    val delayDesc = StaTableSorter.sortAndFilter(nodes, StaSortColumn.DELAY, StaSortDirection.DESCENDING, StaRowFilter.ALL)
    assertEquals(listOf("n_viol", "n_mid", "n_fast"), delayDesc.map { it.id })

    val delayAsc = StaTableSorter.sortAndFilter(nodes, StaSortColumn.DELAY, StaSortDirection.ASCENDING, StaRowFilter.ALL)
    assertEquals(listOf("n_fast", "n_mid", "n_viol"), delayAsc.map { it.id })

    val violations = StaTableSorter.sortAndFilter(nodes, StaSortColumn.SLACK, StaSortDirection.ASCENDING, StaRowFilter.VIOLATING)
    assertEquals(listOf("n_viol"), violations.map { it.id })

    val criticalOnly = StaTableSorter.sortAndFilter(nodes, StaSortColumn.SLACK, StaSortDirection.ASCENDING, StaRowFilter.CRITICAL_PATH)
    assertEquals(listOf("n_viol"), criticalOnly.map { it.id })
  }

  @Test
  fun reportsScreen_displaysSummaryChartsSwitchesToStaTableAndSortsAndInspectsNode() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = PresetRtlDesigns.templates.first().verilogCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success

    var inspectedNodeId: String? = null
    var savedSnapshot = false

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        ReportsScreen(
          state = WorkbenchUiState(
            designName = "counter_8bit",
            synthesisResult = result,
          ),
          settings = AppSettings(),
          onSaveSnapshot = { savedSnapshot = true },
          onSelectNode = { inspectedNodeId = it },
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
    composeTestRule.onNodeWithTag("reports_tab_summary").assertIsDisplayed()
    composeTestRule.onNodeWithTag("reports_tab_sta").assertIsDisplayed()
    composeTestRule.onNodeWithTag("reports_tab_simulate").assertIsDisplayed()
    composeTestRule.onNodeWithTag("reports_tab_schematic").assertIsDisplayed()

    composeTestRule.onNodeWithTag("reports_summary_list")
      .performScrollToNode(hasTestTag("save_ppa_snapshot_button"))
    composeTestRule.onNodeWithTag("save_ppa_snapshot_button").performClick()
    assertTrue(savedSnapshot)

    // Switch to STA Table view
    composeTestRule.onNodeWithTag("reports_tab_sta").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("sta_timing_table").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sort: Slack ↑").assertIsDisplayed()

    composeTestRule.onNodeWithTag("sta_sort_slack").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Sort: Slack ↓").assertIsDisplayed()

    composeTestRule.onNodeWithTag("sta_sort_delay").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Sort: Delay ↓").assertIsDisplayed()

    composeTestRule.onNodeWithTag("sta_filter_critical").performClick()
    composeTestRule.waitForIdle()

    val firstNodeId = result.report.nodes.first { it.isCriticalPath }.id
    composeTestRule.onNodeWithTag("sta_row_$firstNodeId").performClick()
    composeTestRule.waitForIdle()
    assertEquals(firstNodeId, inspectedNodeId)
    composeTestRule.onNodeWithTag("netlist_canvas").assertIsDisplayed()
  }
}
