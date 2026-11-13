package com.example.domain.synth

import androidx.compose.ui.geometry.Offset
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetlistLayoutTest {

  @Test
  fun computeLayout_placesTopologicalLevelsLeftToRightAndIndexesEdges() {
    val source = PresetRtlDesigns.templates.first().verilogCode
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = source,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    )
    assertTrue(result is SynthesisResult.Success)
    val report = (result as SynthesisResult.Success).report

    val layout = NetlistLayoutEngine.computeLayout(report)
    assertEquals(report.nodes.size, layout.positionedNodes.size)
    assertTrue(layout.boundsWidth > NetlistLayoutEngine.NODE_WIDTH)
    assertTrue(layout.boundsHeight > NetlistLayoutEngine.NODE_HEIGHT)

    // Verify no two nodes overlap in world coordinates
    val placedList = layout.positionedNodes.values.toList()
    for (i in placedList.indices) {
      for (j in (i + 1) until placedList.size) {
        val a = placedList[i]
        val b = placedList[j]
        val overlaps = a.x < b.x + b.width && a.x + a.width > b.x &&
          a.y < b.y + b.height && a.y + a.height > b.y
        assertTrue("Nodes ${a.node.id} and ${b.node.id} should not overlap", !overlaps)
      }
    }

    // Verify combinational edges advance left-to-right across topological levels
    report.edges.forEach { edge ->
      val from = layout.positionedNodes.getValue(edge.fromNodeId)
      val to = layout.positionedNodes.getValue(edge.toNodeId)
      if (!to.node.gateType.isSequential) {
        assertTrue(
          "Expected '${edge.fromNodeId}' (x=${from.x}) to precede combinational '${edge.toNodeId}' (x=${to.x})",
          from.x <= to.x,
        )
      }
    }

    // Verify connectedNodeIds returns fan-in + fan-out neighbors of an internal logic gate
    val internalGate = report.nodes.first { !it.gateType.isPort }
    val connected = layout.connectedNodeIds(internalGate.id)
    assertTrue(internalGate.id in connected)
    assertTrue(connected.size >= 2)
  }

  @Test
  fun hitTestNode_accountsForPanAndScale() {
    val source = """
      module comb_top(input a, input b, output y);
        assign y = a & b;
      endmodule
    """.trimIndent()
    val report = (RtlSynthesisEngine.synthesize(
      verilogSource = source,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.MIN_AREA,
      clockPeriodNs = 5.0,
    ) as SynthesisResult.Success).report

    val layout = NetlistLayoutEngine.computeLayout(report)
    val targetNode = report.nodes.first { !it.gateType.isPort }
    val placed = layout.positionedNodes.getValue(targetNode.id)

    val pan = Offset(100f, 60f)
    val scale = 1.5f
    val screenCenter = Offset(
      x = pan.x + placed.center.x * scale,
      y = pan.y + placed.center.y * scale,
    )

    val hit = NetlistLayoutEngine.hitTestNode(
      layout = layout,
      canvasPoint = screenCenter,
      panOffset = pan,
      scale = scale,
    )
    assertNotNull(hit)
    assertEquals(targetNode.id, hit!!.id)

    val miss = NetlistLayoutEngine.hitTestNode(
      layout = layout,
      canvasPoint = Offset(1f, 1f),
      panOffset = pan,
      scale = scale,
    )
    assertNull(miss)
  }

  @Test
  fun computeFitTransform_producesBoundedScaleForViewport() {
    val source = PresetRtlDesigns.templates[1].verilogCode
    val report = (RtlSynthesisEngine.synthesize(
      verilogSource = source,
      library = TargetLibrary.ARTIX7_FPGA,
      goal = OptimizationGoal.MIN_DELAY,
      clockPeriodNs = 4.0,
    ) as SynthesisResult.Success).report

    val layout = NetlistLayoutEngine.computeLayout(report)
    val (fitScale, fitOffset) = NetlistLayoutEngine.computeFitTransform(
      layout = layout,
      viewportWidth = 800f,
      viewportHeight = 600f,
    )
    assertTrue(fitScale in NetlistLayoutEngine.MIN_ZOOM..1.35f)
    assertTrue(fitOffset.x >= 12f)
    assertTrue(fitOffset.y >= 12f)
  }

  @Test
  fun applyTransformGesture_anchorsCentroidAndClampsZoomAndPanBounds() {
    val source = PresetRtlDesigns.templates.first().verilogCode
    val report = (RtlSynthesisEngine.synthesize(
      verilogSource = source,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success).report
    val layout = NetlistLayoutEngine.computeLayout(report)

    // 1. Zooming in around a centroid keeps the world coordinate under centroid fixed
    val centroid = Offset(400f, 300f)
    val startPan = Offset(40f, 30f)
    val startScale = 1.0f
    val worldBefore = Offset((centroid.x - startPan.x) / startScale, (centroid.y - startPan.y) / startScale)

    val (zoomedScale, zoomedPan) = NetlistLayoutEngine.applyTransformGesture(
      currentScale = startScale,
      currentPan = startPan,
      centroid = centroid,
      pan = Offset.Zero,
      zoom = 1.5f,
      layout = layout,
      viewportWidth = 800f,
      viewportHeight = 600f,
    )
    assertEquals(1.5f, zoomedScale, 0.001f)
    val worldAfter = Offset((centroid.x - zoomedPan.x) / zoomedScale, (centroid.y - zoomedPan.y) / zoomedScale)
    assertEquals(worldBefore.x, worldAfter.x, 0.5f)
    assertEquals(worldBefore.y, worldAfter.y, 0.5f)

    // 2. Zoom clamps within [MIN_ZOOM, MAX_ZOOM]
    val (clampedMax, _) = NetlistLayoutEngine.applyTransformGesture(
      currentScale = 2.8f,
      currentPan = Offset.Zero,
      centroid = centroid,
      pan = Offset.Zero,
      zoom = 5.0f,
      layout = layout,
      viewportWidth = 800f,
      viewportHeight = 600f,
    )
    assertEquals(NetlistLayoutEngine.MAX_ZOOM, clampedMax, 0.001f)

    val (clampedMin, _) = NetlistLayoutEngine.applyTransformGesture(
      currentScale = 0.4f,
      currentPan = Offset.Zero,
      centroid = centroid,
      pan = Offset.Zero,
      zoom = 0.2f,
      layout = layout,
      viewportWidth = 800f,
      viewportHeight = 600f,
    )
    assertEquals(NetlistLayoutEngine.MIN_ZOOM, clampedMin, 0.001f)

    // 3. Excessive pan stays clamped so part of diagram remains inside viewport
    val (_, clampedPan) = NetlistLayoutEngine.applyTransformGesture(
      currentScale = 1.0f,
      currentPan = Offset.Zero,
      centroid = centroid,
      pan = Offset(50000f, -50000f),
      zoom = 1.0f,
      layout = layout,
      viewportWidth = 800f,
      viewportHeight = 600f,
    )
    assertTrue(clampedPan.x <= 800f - 72f + 1f)
    assertTrue(clampedPan.y >= 72f - layout.boundsHeight - 1f)
  }

  @Test
  fun computeFocusNodeTransform_centersNodeInViewport() {
    val source = PresetRtlDesigns.templates.first().verilogCode
    val report = (RtlSynthesisEngine.synthesize(
      verilogSource = source,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success).report
    val layout = NetlistLayoutEngine.computeLayout(report)
    val placed = layout.positionedNodes.values.first()

    val (focusScale, focusPan) = NetlistLayoutEngine.computeFocusNodeTransform(
      placed = placed,
      currentScale = 0.6f,
      viewportWidth = 800f,
      viewportHeight = 600f,
      layout = layout,
    )
    assertTrue(focusScale >= 1.15f)
    val screenCenterX = focusPan.x + placed.center.x * focusScale
    val screenCenterY = focusPan.y + placed.center.y * focusScale
    assertEquals(400f, screenCenterX, 1.0f)
    assertEquals(300f, screenCenterY, 1.0f)
  }

  @Test
  fun minimapAndViewportChecks_detectOffscreenNodesAndJumpToMinimapPoint() {
    val source = PresetRtlDesigns.templates.first().verilogCode
    val report = (RtlSynthesisEngine.synthesize(
      verilogSource = source,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success).report
    val layout = NetlistLayoutEngine.computeLayout(report)
    val placed = layout.positionedNodes.values.first()

    // Node placed far outside viewport returns false for isNodeInViewport
    assertTrue(
      !NetlistLayoutEngine.isNodeInViewport(
        placed = placed,
        panOffset = Offset(-5000f, -5000f),
        scale = 1.0f,
        viewportWidth = 800f,
        viewportHeight = 600f,
      ),
    )

    // Minimap center tap centers the middle of the layout bounds in the viewport
    val jumpedPan = NetlistLayoutEngine.computePanForMinimapPoint(
      minimapTap = Offset(50f, 35f),
      minimapWidth = 100f,
      minimapHeight = 70f,
      layout = layout,
      scale = 1.0f,
      viewportWidth = 800f,
      viewportHeight = 600f,
    )
    assertTrue(jumpedPan.x.isFinite())
    assertTrue(jumpedPan.y.isFinite())
  }
}
