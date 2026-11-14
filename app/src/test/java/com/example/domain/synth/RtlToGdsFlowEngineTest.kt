package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RtlToGdsFlowEngineTest {

  @Test
  fun gdsiiBinaryWriter_emitsValidHeaderTrailerAndSha256() {
    val artifact = GdsiiBinaryWriter.writeStream(
      topModuleName = "inv_chain",
      polygons = listOf(
        GdsRectPolygon(PhysicalMaskLayer.DIE_AREA, 0.0, 0.0, 20.0, 20.0),
        GdsRectPolygon(PhysicalMaskLayer.CELLS, 4.0, 4.0, 2.5, 2.72),
        GdsRectPolygon(PhysicalMaskLayer.MET1, 4.0, 5.0, 6.0, 0.2),
      ),
      pinLabels = listOf(
        GdsRectPolygon(PhysicalMaskLayer.IO_PINS, 1.0, 5.0, 0.5, 0.5, label = "clk", netName = "clk"),
      ),
    )

    assertTrue(artifact.bytes.size > 64)
    assertEquals(64, artifact.sha256Hex.length)
    // GDSII HEADER record: length=6 (0x0006), recordType=0x0002, version=600 (0x0258)
    assertEquals(0x00.toByte(), artifact.bytes[0])
    assertEquals(0x06.toByte(), artifact.bytes[1])
    assertEquals(0x00.toByte(), artifact.bytes[2])
    assertEquals(0x02.toByte(), artifact.bytes[3])
    assertEquals(0x02.toByte(), artifact.bytes[4])
    assertEquals(0x58.toByte(), artifact.bytes[5])

    // Final record: ENDLIB = 0x0004 0x0400
    val n = artifact.bytes.size
    assertEquals(0x00.toByte(), artifact.bytes[n - 4])
    assertEquals(0x04.toByte(), artifact.bytes[n - 3])
    assertEquals(0x04.toByte(), artifact.bytes[n - 2])
    assertEquals(0x00.toByte(), artifact.bytes[n - 1])
  }

  @Test
  fun runFlow_executesAllSevenStagesDeterministicallyAndGeneratesGdsAndTcl() = runTest {
    val rtl = """
      module counter_4b(input wire clk, input wire rst, output reg [3:0] q);
        always @(posedge clk) begin
          if (rst) q <= 4'd0;
          else q <= q + 4'd1;
        end
      endmodule
    """.trimIndent()

    val events = mutableListOf<RtlToGdsStageEvent>()
    val finalEvent = RtlToGdsFlowEngine.runFlow(
      rtlSource = rtl,
      designName = "counter_4b",
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      engineProfile = SynthesisEngineProfile.SILICON_FLOW,
      clockPeriodNs = 5.0,
      coreUtilizationPercent = 65,
      sdcText = "create_clock -name clk -period 5.0 [get_ports clk]",
      stepDelayMs = 0L,
      onProgress = { events += it },
    )

    assertTrue(finalEvent.isFinished)
    assertEquals(7, finalEvent.stageStates.size)
    assertTrue(finalEvent.stageStates.all { it.status == FlowStageStatus.PASSED })
    assertTrue(events.isNotEmpty())

    val layout = finalEvent.layoutSnapshot
    assertNotNull(layout)
    layout!!
    assertEquals("counter_4b", layout.moduleName)
    assertEquals(65, layout.coreUtilizationPercent)
    assertTrue(layout.placedCells.isNotEmpty())
    assertTrue(layout.totalWirelengthUm > 0.0)
    assertTrue(layout.gdsBytes.size > 100)
    assertTrue(layout.tclScript.contains("initialize_floorplan"))
    assertTrue(layout.tclScript.contains("clock_tree_synthesis"))
    assertTrue(layout.tclScript.contains("gds write counter_4b.gds"))

    // Verify hierarchical GDSII stream integrity & KLayout snapshot output
    val parsedGds = KLayoutSnapshotRenderer.parseAndVerifyGds(layout.gdsBytes)
    assertTrue("Expected valid GDSII stream: ${parsedGds.errorMessage}", parsedGds.isValid)
    assertEquals(600, parsedGds.headerVersion)
    assertEquals("counter_4b", parsedGds.topStructureName)
    assertTrue(parsedGds.structureCount >= 2)
    assertTrue(parsedGds.srefCount > 0)
    assertTrue(parsedGds.flattenedPolygons.any { it.layer == PhysicalMaskLayer.NWELL.layerNumber })
    assertTrue(parsedGds.flattenedPolygons.any { it.layer == PhysicalMaskLayer.DIFF.layerNumber })
    assertTrue(parsedGds.flattenedPolygons.any { it.layer == PhysicalMaskLayer.POLY.layerNumber })
    assertTrue(parsedGds.flattenedPolygons.any { it.layer == PhysicalMaskLayer.LI1.layerNumber })
    assertTrue(parsedGds.flattenedPolygons.any { it.layer == PhysicalMaskLayer.MET1.layerNumber })
    assertTrue(parsedGds.flattenedPolygons.any { it.layer == PhysicalMaskLayer.MET2.layerNumber })
    assertTrue(parsedGds.flattenedPolygons.any { it.layer == PhysicalMaskLayer.MET3.layerNumber })
    assertTrue(parsedGds.flattenedPolygons.any { it.layer == PhysicalMaskLayer.CLK_TREE.layerNumber })
    assertTrue(layout.gdsVerified)
    assertNotNull(layout.klayoutSnapshotBitmap)
    assertTrue(layout.klayoutSnapshotPng.size > 1000)
    // Verify PNG signature bytes: 89 50 4E 47
    assertEquals(0x89.toByte(), layout.klayoutSnapshotPng[0])
    assertEquals(0x50.toByte(), layout.klayoutSnapshotPng[1])
    assertEquals(0x4E.toByte(), layout.klayoutSnapshotPng[2])
    assertEquals(0x47.toByte(), layout.klayoutSnapshotPng[3])
    assertTrue(layout.klayoutLypXml.contains("<layer-properties>"))
  }

  @Test
  fun runFlow_resumesCleanlyFromMidPipelineStage() = runTest {
    val rtl = "module inv_buf(input wire a, output wire y); assign y = ~a; endmodule"
    val preStages = RtlToGdsFlowEngine.initialStages().mapIndexed { idx, st ->
      if (idx < 3) st.copy(status = FlowStageStatus.PASSED, durationMs = 5L, headlineMetric = "Prior OK")
      else st
    }

    val resumed = RtlToGdsFlowEngine.runFlow(
      rtlSource = rtl,
      designName = "inv_buf",
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.MIN_DELAY,
      engineProfile = SynthesisEngineProfile.YOSYS_ABC,
      clockPeriodNs = 2.0,
      coreUtilizationPercent = 70,
      sdcText = "",
      existingStages = preStages,
      startFromStageIndex = 3,
      stepDelayMs = 0L,
      onProgress = {},
    )

    assertTrue(resumed.isFinished)
    assertTrue(resumed.stageStates.all { it.status == FlowStageStatus.PASSED })
    assertEquals("Prior OK", resumed.stageStates[0].headlineMetric)
    assertNotNull(resumed.layoutSnapshot)
  }
}
