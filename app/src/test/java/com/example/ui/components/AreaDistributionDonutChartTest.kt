package com.example.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.AreaAnalysisHelper
import com.example.domain.synth.ResourceAreaBucket
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SynthesisResult
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
class AreaDistributionDonutChartTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val macRtl = """
    module mac_dsp(
      input wire clk,
      input wire rst,
      input wire [7:0] a,
      input wire [7:0] b,
      output reg [15:0] acc
    );
      wire [15:0] prod;
      assign prod = a * b;
      always @(posedge clk) begin
        if (rst)
          acc <= 16'd0;
        else
          acc <= acc + prod;
      end
    endmodule
  """.trimIndent()

  private val counterRtl = """
    module simple_ctr(
      input wire clk,
      input wire rst,
      output reg [3:0] q
    );
      always @(posedge clk) begin
        if (rst)
          q <= 4'd0;
        else
          q <= q + 4'd1;
      end
    endmodule
  """.trimIndent()

  @Test
  fun resourceSlices_bucketLutsFlipFlopsAndDspsAccuratelyOnFpgaMac() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = macRtl,
      library = TargetLibrary.ARTIX7_FPGA,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 10.0,
    )
    assertTrue(result is SynthesisResult.Success)
    val report = (result as SynthesisResult.Success).report
    val summary = AreaAnalysisHelper.analyze(report, layout = null)

    assertEquals(3, summary.resourceSlices.size)
    val lutSlice = summary.resourceSlices.first { it.bucket == ResourceAreaBucket.LUTS }
    val ffSlice = summary.resourceSlices.first { it.bucket == ResourceAreaBucket.FLIP_FLOPS }
    val dspSlice = summary.resourceSlices.first { it.bucket == ResourceAreaBucket.DSPS }

    assertTrue("Expected LUTs > 0", lutSlice.cellCount > 0 && lutSlice.areaUm2 > 0.0)
    assertTrue("Expected Flip-Flops > 0", ffSlice.cellCount > 0 && ffSlice.areaUm2 > 0.0)
    assertTrue("Expected DSP48E1 > 0", dspSlice.cellCount > 0 && dspSlice.areaUm2 > 0.0)
    val pctSum = summary.resourceSlices.sumOf { it.areaPct }
    assertEquals(100.0, pctSum, 0.2)
  }

  @Test
  fun resourceSlices_reportsZeroDspsWithoutFabricatingValues() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = counterRtl,
      library = TargetLibrary.ARTIX7_FPGA,
      goal = OptimizationGoal.MIN_AREA,
      clockPeriodNs = 10.0,
    )
    val report = (result as SynthesisResult.Success).report
    val summary = AreaAnalysisHelper.analyze(report, layout = null)

    val dspSlice = summary.resourceSlices.first { it.bucket == ResourceAreaBucket.DSPS }
    assertEquals(0, dspSlice.cellCount)
    assertEquals(0.0, dspSlice.areaUm2, 1e-9)
    assertEquals(0.0, dspSlice.areaPct, 1e-9)
    assertTrue(dspSlice.instanceNodeIds.isEmpty())
  }

  @Test
  fun donutCard_rendersAllBucketsSelectsSliceAndTriggersSchematicLocate() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = macRtl,
      library = TargetLibrary.ARTIX7_FPGA,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 10.0,
    )
    val report = (result as SynthesisResult.Success).report
    val summary = AreaAnalysisHelper.analyze(report, layout = null)
    val expectedDspIds = summary.resourceSlices.first { it.bucket == ResourceAreaBucket.DSPS }.instanceNodeIds.toSet()

    var locatedIds: Set<String> = emptySet()

    composeTestRule.setContent {
      RtlSynthTheme {
        AreaDistributionDonutCard(
          summary = summary,
          isFpga = true,
          onLocateBucketInSchematic = { locatedIds = it },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("area_resource_donut_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("area_resource_donut_canvas").assertIsDisplayed()
    composeTestRule.onNodeWithTag("area_donut_legend_luts").assertIsDisplayed()
    composeTestRule.onNodeWithTag("area_donut_legend_flip_flops").assertIsDisplayed()
    composeTestRule.onNodeWithTag("area_donut_legend_dsps").assertIsDisplayed()

    // Select DSP slice from legend and trigger Locate in Schematic
    composeTestRule.onNodeWithTag("area_donut_legend_dsps").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("area_donut_selected_detail").assertIsDisplayed()
    composeTestRule.onNodeWithTag("area_donut_locate_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(expectedDspIds, locatedIds)

    // Switch selection to Flip-Flops and check center value contains %
    composeTestRule.onNodeWithTag("area_donut_legend_flip_flops").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("area_donut_center_value").assertTextContains("%", substring = true)
  }

  @Test
  fun donutCard_showsNoAreaDataWhenSynthesisEmpty() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = counterRtl,
      library = TargetLibrary.ARTIX7_FPGA,
      goal = OptimizationGoal.MIN_AREA,
      clockPeriodNs = 10.0,
    )
    val baseReport = (result as SynthesisResult.Success).report
    val emptyReport = baseReport.copy(nodes = emptyList(), totalAreaUm2 = 0.0, cellCount = 0)
    val emptySummary = AreaAnalysisHelper.analyze(emptyReport, layout = null)

    composeTestRule.setContent {
      RtlSynthTheme {
        AreaDistributionDonutCard(
          summary = emptySummary,
          isFpga = true,
          onLocateBucketInSchematic = null,
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("area_donut_center_value").assertTextContains("N/A")
    composeTestRule.onNodeWithText("NO AREA DATA").assertIsDisplayed()
  }
}
