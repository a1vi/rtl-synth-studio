package com.example.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.DftConfig
import com.example.domain.synth.DftScanStyle
import com.example.domain.synth.FaultStatus
import com.example.domain.synth.FaultType
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.DftFaultAnalysisCard
import com.example.ui.components.DftFaultAnalysisHelper
import com.example.ui.components.DftFaultModelFilter
import com.example.ui.components.DftFaultStatusFilter
import com.example.ui.components.FaultKpiStatusTier
import com.example.ui.components.FaultModelSummaryRow
import com.example.ui.theme.RtlSynthTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h892dp")
class DftFaultAnalysisTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun faultAnalysisHelper_computesAccurateStuckAtAndTransitionDelayBreakdowns() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = PresetRtlDesigns.templates.first().verilogCode,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
      dftConfig = DftConfig(
        scanInsertionEnabled = true,
        scanStyle = DftScanStyle.MUXED_D,
        targetChainCount = 1,
        atpgMaxPatterns = 32,
      ),
    ) as SynthesisResult.Success

    val dft = result.report.dftReport!!
    assertEquals(dft.totalFaults, dft.sampleFaults.size)
    assertTrue("Expected non-empty SA0/SA1 fault list", dft.sampleFaults.isNotEmpty())
    assertTrue("Expected non-empty STR/STF transition fault list", dft.transitionFaults.isNotEmpty())
    assertTrue(dft.transitionFaults.any { it.faultType == FaultType.STR })
    assertTrue(dft.transitionFaults.any { it.faultType == FaultType.STF })

    val allRows = DftFaultAnalysisHelper.buildSummaryRows(dft, DftFaultModelFilter.ALL_MODELS)
    assertEquals(listOf("SA0", "SA1", "STR", "STF"), allRows.map { it.key })
    allRows.forEach { row ->
      assertEquals(row.total, row.detected + row.undetected + row.untestable)
    }

    val allTotal = DftFaultAnalysisHelper.buildTotalRow(allRows)
    assertEquals(dft.sampleFaults.size + dft.transitionFaults.size, allTotal.total)

    val stuckAtRows = DftFaultAnalysisHelper.buildSummaryRows(dft, DftFaultModelFilter.COMBINED_SA)
    assertEquals(listOf("SA0", "SA1"), stuckAtRows.map { it.key })
    val stuckAtTotal = DftFaultAnalysisHelper.buildTotalRow(stuckAtRows)
    assertEquals(dft.totalFaults, stuckAtTotal.total)
    assertEquals(dft.detectedFaults, stuckAtTotal.detected)
    assertEquals(dft.undetectedFaults, stuckAtTotal.undetected)

    val transRows = DftFaultAnalysisHelper.buildSummaryRows(dft, DftFaultModelFilter.TRANSITION_DELAY)
    assertEquals(listOf("STR", "STF"), transRows.map { it.key })
    val transTotal = DftFaultAnalysisHelper.buildTotalRow(transRows)
    assertEquals(dft.transitionFaults.size, transTotal.total)
    assertEquals(dft.transitionFaults.count { it.status == FaultStatus.DETECTED }, transTotal.detected)
    assertEquals(dft.transitionFaults.count { it.status == FaultStatus.UNDETECTED }, transTotal.undetected)

    // Verify KPI status tier classification across backend states
    val passKpi = DftFaultAnalysisHelper.buildKpiSummary(
      dft = dft.copy(scanInserted = true),
      summary = FaultModelSummaryRow(null, "TOTAL", "Total", "Combined", total = 100, detected = 95, undetected = 5, untestable = 0),
    )
    assertEquals(FaultKpiStatusTier.PASS, passKpi.coverageStatus.tier)
    assertEquals(FaultKpiStatusTier.PASS, passKpi.untestableStatus.tier)
    assertEquals("PASS", passKpi.coverageStatus.label)
    assertEquals("CLEAN", passKpi.untestableStatus.label)

    val marginalKpi = DftFaultAnalysisHelper.buildKpiSummary(
      dft = dft.copy(scanInserted = true),
      summary = FaultModelSummaryRow(null, "TOTAL", "Total", "Combined", total = 100, detected = 78, undetected = 18, untestable = 4),
    )
    assertEquals(FaultKpiStatusTier.WARNING, marginalKpi.coverageStatus.tier)
    assertEquals(FaultKpiStatusTier.WARNING, marginalKpi.untestableStatus.tier)
    assertEquals("BLOCKED", marginalKpi.untestableStatus.label)

    val criticalNoScanKpi = DftFaultAnalysisHelper.buildKpiSummary(
      dft = dft.copy(scanInserted = false),
      summary = FaultModelSummaryRow(null, "TOTAL", "Total", "Combined", total = 100, detected = 40, undetected = 20, untestable = 40),
    )
    assertEquals(FaultKpiStatusTier.CRITICAL, criticalNoScanKpi.coverageStatus.tier)
    assertEquals(FaultKpiStatusTier.CRITICAL, criticalNoScanKpi.untestableStatus.tier)
    assertEquals("NO SCAN", criticalNoScanKpi.untestableStatus.label)

    val emptyKpi = DftFaultAnalysisHelper.buildKpiSummary(
      dft = dft,
      summary = FaultModelSummaryRow(null, "TOTAL", "Total", "Combined", total = 0, detected = 0, undetected = 0, untestable = 0),
    )
    assertEquals(FaultKpiStatusTier.NEUTRAL, emptyKpi.totalStatus.tier)
    assertEquals(FaultKpiStatusTier.NEUTRAL, emptyKpi.coverageStatus.tier)
    assertEquals(FaultKpiStatusTier.NEUTRAL, emptyKpi.untestableStatus.tier)
  }

  @Test
  fun faultAnalysisCard_displaysSummaryTableAndFiltersStuckAtAndTransitionDelay() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = PresetRtlDesigns.templates.first().verilogCode,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
      dftConfig = DftConfig(
        scanInsertionEnabled = true,
        scanStyle = DftScanStyle.MUXED_D,
        targetChainCount = 2,
        atpgMaxPatterns = 32,
      ),
    ) as SynthesisResult.Success

    val dft = result.report.dftReport!!
    val allCombined = DftFaultAnalysisHelper.collectAllFaults(dft)
    val sa0Count = dft.sampleFaults.count { it.faultType == FaultType.SA0 }
    val transTotal = dft.transitionFaults.size
    val transDet = dft.transitionFaults.count { it.status == FaultStatus.DETECTED }
    val transUndet = dft.transitionFaults.count { it.status == FaultStatus.UNDETECTED }

    var locatedNodeId = ""

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          DftFaultAnalysisCard(
            dft = dft,
            onLocateNodeInSchematic = { locatedNodeId = it },
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_fault_analysis_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_kpi_section").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_kpi_total_faults_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_kpi_fault_coverage_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_kpi_untestable_faults_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_summary_table").assertIsDisplayed()

    // Confirm KPI summary card section is positioned visually above the fault summary table
    val kpiBounds = composeTestRule.onNodeWithTag("dft_fault_kpi_section").getUnclippedBoundsInRoot()
    val tableBounds = composeTestRule.onNodeWithTag("dft_fault_summary_table").getUnclippedBoundsInRoot()
    assertTrue("KPI summary section must appear above the fault table", kpiBounds.bottom <= tableBounds.top)

    val allCovPct = if (allCombined.isNotEmpty()) {
      (allCombined.count { it.status == FaultStatus.DETECTED } * 100.0) / allCombined.size
    } else 100.0
    composeTestRule.onNodeWithTag("dft_kpi_total_faults_value")
      .assertTextEquals(allCombined.size.toString())
    composeTestRule.onNodeWithTag("dft_kpi_fault_coverage_value")
      .assertTextEquals("${"%.1f".format(Locale.US, allCovPct)}%")
    composeTestRule.onNodeWithTag("dft_kpi_untestable_faults_value")
      .assertTextEquals(allCombined.count { it.status == FaultStatus.UNTESTABLE }.toString())
    composeTestRule.onNodeWithTag("dft_kpi_total_faults_status").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_kpi_fault_coverage_status").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_kpi_untestable_faults_status").assertIsDisplayed()

    composeTestRule.onNodeWithTag("dft_fault_row_SA0").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_row_SA1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_row_STR").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_row_STF").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_row_TOTAL").assertIsDisplayed()

    composeTestRule.onNodeWithTag("dft_fault_summary_total_value")
      .assertTextEquals(allCombined.size.toString())
    composeTestRule.onNodeWithTag("dft_fault_summary_detected_value")
      .assertTextEquals(allCombined.count { it.status == FaultStatus.DETECTED }.toString())
    composeTestRule.onNodeWithTag("dft_fault_summary_undetected_value")
      .assertTextEquals(allCombined.count { it.status == FaultStatus.UNDETECTED }.toString())

    // Filter to Stuck-At (SA0+SA1)
    composeTestRule.onNodeWithTag("dft_fault_model_combined_sa").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_fault_row_SA0").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_row_SA1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_row_STR").assertDoesNotExist()
    composeTestRule.onNodeWithTag("dft_fault_row_STF").assertDoesNotExist()
    composeTestRule.onNodeWithTag("dft_fault_summary_total_value")
      .assertTextEquals(dft.totalFaults.toString())
    composeTestRule.onNodeWithTag("dft_fault_summary_detected_value")
      .assertTextEquals(dft.detectedFaults.toString())
    composeTestRule.onNodeWithTag("dft_fault_summary_undetected_value")
      .assertTextEquals(dft.undetectedFaults.toString())

    // Filter to SA0 only
    composeTestRule.onNodeWithTag("dft_fault_model_sa0_only").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_fault_row_SA0").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_row_SA1").assertDoesNotExist()
    composeTestRule.onNodeWithTag("dft_fault_summary_total_value")
      .assertTextEquals(sa0Count.toString())

    // Filter to Transition Delay (STR+STF)
    composeTestRule.onNodeWithTag("dft_fault_model_transition_delay").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_unsupported_fault_model_notice").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_row_STR").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_row_STF").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_row_SA0").assertDoesNotExist()
    composeTestRule.onNodeWithTag("dft_fault_summary_total_value")
      .assertTextEquals(transTotal.toString())
    composeTestRule.onNodeWithTag("dft_fault_summary_detected_value")
      .assertTextEquals(transDet.toString())
    composeTestRule.onNodeWithTag("dft_fault_summary_undetected_value")
      .assertTextEquals(transUndet.toString())
    composeTestRule.onNodeWithTag("dft_kpi_total_faults_value")
      .assertTextEquals(transTotal.toString())
    val transCovPct = if (transTotal > 0) (transDet * 100.0) / transTotal else 100.0
    composeTestRule.onNodeWithTag("dft_kpi_fault_coverage_value")
      .assertTextEquals("${"%.1f".format(Locale.US, transCovPct)}%")

    // Exercise Status filter and Search + Schematic locate callback
    composeTestRule.onNodeWithTag("dft_fault_model_all").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("dft_fault_filter_${DftFaultStatusFilter.DETECTED.name}")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_fault_sites_table").performScrollTo().assertIsDisplayed()

    val firstDetected = allCombined.first { it.status == FaultStatus.DETECTED }
    composeTestRule.onNodeWithTag("dft_fault_search_input")
      .performScrollTo()
      .performTextReplacement(firstDetected.netName)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_fault_site_row_${firstDetected.id}")
      .performScrollTo()
      .performClick()
    assertEquals(firstDetected.nodeId, locatedNodeId)

    composeTestRule.onNodeWithTag("dft_fault_search_clear").performClick()
    composeTestRule.waitForIdle()
    assertFalse(DftFaultAnalysisHelper.filterFaultSites(allCombined, DftFaultStatusFilter.ALL, "").isEmpty())
  }

  @Test
  fun faultAnalysisCard_rendersUntestableFootnoteInLightThemeForNonScanSequential() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = PresetRtlDesigns.templates.first().verilogCode,
      library = TargetLibrary.NANGATE45_45NM,
      goal = OptimizationGoal.MIN_AREA,
      clockPeriodNs = 4.0,
      dftConfig = DftConfig(scanInsertionEnabled = false),
    ) as SynthesisResult.Success

    val dft = result.report.dftReport!!
    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = false) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          DftFaultAnalysisCard(
            dft = dft,
            onLocateNodeInSchematic = {},
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_fault_kpi_section").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_kpi_untestable_faults_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_fault_summary_table").assertIsDisplayed()
    if (dft.untestableFaults > 0 || dft.transitionFaults.any { it.status == FaultStatus.UNTESTABLE }) {
      composeTestRule.onNodeWithTag("dft_fault_untestable_footnote")
        .performScrollTo()
        .assertIsDisplayed()
    }
  }
}
