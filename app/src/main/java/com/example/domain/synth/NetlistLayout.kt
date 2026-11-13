package com.example.domain.synth

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.min

data class PositionedGateNode(
  val node: GateNode,
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
) {
  val rect: Rect
    get() = Rect(left = x, top = y, right = x + width, bottom = y + height)

  val center: Offset
    get() = Offset(x + width / 2f, y + height / 2f)

  val leftPinCenter: Offset
    get() = Offset(x, y + height / 2f)

  val rightPinCenter: Offset
    get() = Offset(x + width, y + height / 2f)
}

data class NetlistGraphLayout(
  val positionedNodes: Map<String, PositionedGateNode>,
  val boundsWidth: Float,
  val boundsHeight: Float,
  val fanInEdges: Map<String, List<NetEdge>>,
  val fanOutEdges: Map<String, List<NetEdge>>,
) {
  fun connectedNodeIds(nodeId: String): Set<String> {
    val result = mutableSetOf(nodeId)
    fanInEdges[nodeId].orEmpty().forEach { result += it.fromNodeId }
    fanOutEdges[nodeId].orEmpty().forEach { result += it.toNodeId }
    return result
  }

  fun connectedEdges(nodeId: String): Set<NetEdge> {
    val result = mutableSetOf<NetEdge>()
    result += fanInEdges[nodeId].orEmpty()
    result += fanOutEdges[nodeId].orEmpty()
    return result
  }
}

object NetlistLayoutEngine {
  const val NODE_WIDTH = 164f
  const val NODE_HEIGHT = 72f
  const val COL_SPACING = 248f
  const val ROW_SPACING = 104f
  const val WORLD_PADDING = 48f
  const val MIN_ZOOM = 0.20f
  const val MAX_ZOOM = 4.00f

  fun computeLayout(
    report: SynthesisReport,
    manualPositions: Map<String, Offset> = emptyMap(),
  ): NetlistGraphLayout {
    val fanIn = report.edges.groupBy { it.toNodeId }
    val fanOut = report.edges.groupBy { it.fromNodeId }

    val groupedByLevel = report.nodes.groupBy { it.level }.toSortedMap()
    val maxRowsInAnyCol = groupedByLevel.values.maxOfOrNull { it.size } ?: 1
    val totalGridHeight = maxRowsInAnyCol * ROW_SPACING

    val placed = linkedMapOf<String, PositionedGateNode>()

    groupedByLevel.entries.forEachIndexed { colIdx, (_, colNodes) ->
      // Order nodes within each level using barycenter of already-placed upstream nodes
      // while keeping critical-path nodes prominent and preserving deterministic tie-breaking.
      val sorted = colNodes.sortedWith(
        compareBy<GateNode> { node ->
          val incomingY = fanIn[node.id]
            .orEmpty()
            .mapNotNull { edge -> placed[edge.fromNodeId]?.y }
          if (incomingY.isNotEmpty()) incomingY.average() else Double.MAX_VALUE
        }
          .thenByDescending { it.isCriticalPath }
          .thenBy { it.id }
      )

      val colHeight = sorted.size * ROW_SPACING
      val verticalCenterOffset = ((totalGridHeight - colHeight) / 2f).coerceAtLeast(0f)

      sorted.forEachIndexed { rowIdx, node ->
        val overridePos = manualPositions[node.id]
        val x = overridePos?.x ?: (WORLD_PADDING + colIdx * COL_SPACING)
        val y = overridePos?.y ?: (WORLD_PADDING + verticalCenterOffset + rowIdx * ROW_SPACING)
        placed[node.id] = PositionedGateNode(
          node = node,
          x = x,
          y = y,
          width = NODE_WIDTH,
          height = NODE_HEIGHT,
        )
      }
    }

    val minX = placed.values.minOfOrNull { it.x } ?: WORLD_PADDING
    val minY = placed.values.minOfOrNull { it.y } ?: WORLD_PADDING
    val maxX = placed.values.maxOfOrNull { it.x + it.width } ?: (WORLD_PADDING + NODE_WIDTH)
    val maxY = placed.values.maxOfOrNull { it.y + it.height } ?: (WORLD_PADDING + NODE_HEIGHT)
    val spanW = (maxX - minOf(0f, minX) + WORLD_PADDING).coerceAtLeast(720f)
    val spanH = (maxY - minOf(0f, minY) + WORLD_PADDING).coerceAtLeast(460f)

    return NetlistGraphLayout(
      positionedNodes = placed,
      boundsWidth = spanW,
      boundsHeight = spanH,
      fanInEdges = fanIn,
      fanOutEdges = fanOut,
    )
  }

  fun hitTestNode(
    layout: NetlistGraphLayout,
    canvasPoint: Offset,
    panOffset: Offset,
    scale: Float,
    touchSlopWorld: Float = 12f,
  ): GateNode? {
    if (scale <= 0.001f) return null
    val worldPoint = Offset(
      x = (canvasPoint.x - panOffset.x) / scale,
      y = (canvasPoint.y - panOffset.y) / scale,
    )
    return layout.positionedNodes.values.firstOrNull { pos ->
      worldPoint.x >= pos.x - touchSlopWorld &&
        worldPoint.x <= pos.x + pos.width + touchSlopWorld &&
        worldPoint.y >= pos.y - touchSlopWorld &&
        worldPoint.y <= pos.y + pos.height + touchSlopWorld
    }?.node
  }

  fun computeFitTransform(
    layout: NetlistGraphLayout,
    viewportWidth: Float,
    viewportHeight: Float,
  ): Pair<Float, Offset> {
    if (viewportWidth <= 1f || viewportHeight <= 1f || layout.boundsWidth <= 1f || layout.boundsHeight <= 1f) {
      return 1f to Offset(24f, 24f)
    }
    val scaleX = viewportWidth / layout.boundsWidth
    val scaleY = viewportHeight / layout.boundsHeight
    val fitScale = min(scaleX, scaleY).coerceIn(MIN_ZOOM, 1.35f)
    val contentW = layout.boundsWidth * fitScale
    val contentH = layout.boundsHeight * fitScale
    val offsetX = ((viewportWidth - contentW) / 2f).coerceAtLeast(12f)
    val offsetY = ((viewportHeight - contentH) / 2f).coerceAtLeast(12f)
    return fitScale to Offset(offsetX, offsetY)
  }

  fun clampPanOffset(
    pan: Offset,
    scale: Float,
    layout: NetlistGraphLayout,
    viewportWidth: Float,
    viewportHeight: Float,
  ): Offset {
    if (viewportWidth <= 1f || viewportHeight <= 1f) return pan
    val scaledW = layout.boundsWidth * scale
    val scaledH = layout.boundsHeight * scale
    val marginX = min(72f, viewportWidth * 0.25f)
    val marginY = min(72f, viewportHeight * 0.25f)
    val minX = marginX - scaledW
    val maxX = viewportWidth - marginX
    val minY = marginY - scaledH
    val maxY = viewportHeight - marginY
    return Offset(
      x = pan.x.coerceIn(minOf(minX, maxX), maxOf(minX, maxX)),
      y = pan.y.coerceIn(minOf(minY, maxY), maxOf(minY, maxY)),
    )
  }

  fun applyTransformGesture(
    currentScale: Float,
    currentPan: Offset,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
    layout: NetlistGraphLayout,
    viewportWidth: Float,
    viewportHeight: Float,
  ): Pair<Float, Offset> {
    val safeZoom = if (zoom.isFinite() && zoom > 0f) zoom else 1f
    val newScale = (currentScale * safeZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val zoomFactor = if (currentScale > 0.0001f) newScale / currentScale else 1f
    val rawPan = centroid - (centroid - currentPan) * zoomFactor + pan
    val clampedPan = clampPanOffset(rawPan, newScale, layout, viewportWidth, viewportHeight)
    return newScale to clampedPan
  }

  fun computeFocusNodeTransform(
    placed: PositionedGateNode,
    currentScale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    layout: NetlistGraphLayout,
  ): Pair<Float, Offset> {
    val targetScale = currentScale.coerceAtLeast(1.15f).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val rawPan = Offset(
      x = viewportWidth / 2f - placed.center.x * targetScale,
      y = viewportHeight / 2f - placed.center.y * targetScale,
    )
    return targetScale to clampPanOffset(rawPan, targetScale, layout, viewportWidth, viewportHeight)
  }

  fun isNodeInViewport(
    placed: PositionedGateNode,
    panOffset: Offset,
    scale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    marginPx: Float = 32f,
  ): Boolean {
    if (viewportWidth <= 1f || viewportHeight <= 1f) return true
    val left = panOffset.x + placed.x * scale
    val right = panOffset.x + (placed.x + placed.width) * scale
    val top = panOffset.y + placed.y * scale
    val bottom = panOffset.y + (placed.y + placed.height) * scale
    return right >= marginPx &&
      left <= viewportWidth - marginPx &&
      bottom >= marginPx &&
      top <= viewportHeight - marginPx
  }

  fun computePanForMinimapPoint(
    minimapTap: Offset,
    minimapWidth: Float,
    minimapHeight: Float,
    layout: NetlistGraphLayout,
    scale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
  ): Offset {
    if (minimapWidth <= 1f || minimapHeight <= 1f || layout.boundsWidth <= 1f || layout.boundsHeight <= 1f) {
      return Offset.Zero
    }
    val worldX = (minimapTap.x / minimapWidth).coerceIn(0f, 1f) * layout.boundsWidth
    val worldY = (minimapTap.y / minimapHeight).coerceIn(0f, 1f) * layout.boundsHeight
    val rawPan = Offset(
      x = viewportWidth / 2f - worldX * scale,
      y = viewportHeight / 2f - worldY * scale,
    )
    return clampPanOffset(rawPan, scale, layout, viewportWidth, viewportHeight)
  }
}
