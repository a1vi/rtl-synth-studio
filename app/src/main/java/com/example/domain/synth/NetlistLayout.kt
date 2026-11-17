package com.example.domain.synth

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
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

// ==================== CADENCE ORTHOGONAL SCHEMATIC LAYOUT MODELS ====================

data class PlacedSchematicCell(
  val cell: SchematicCell,
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
  val inputPinOffsets: List<Offset>,
  val outputPinOffsets: List<Offset>,
) {
  val rect: Rect
    get() = Rect(left = x, top = y, right = x + width, bottom = y + height)

  val center: Offset
    get() = Offset(x + width / 2f, y + height / 2f)

  fun inputPinOffset(pinName: String): Offset {
    val idx = cell.inputPins.indexOfFirst { it.pinName == pinName }
    return if (idx >= 0 && idx < inputPinOffsets.size) inputPinOffsets[idx] else Offset(x, y + height / 2f)
  }

  fun outputPinOffset(pinName: String): Offset {
    val idx = cell.outputPins.indexOfFirst { it.pinName == pinName }
    return if (idx >= 0 && idx < outputPinOffsets.size) outputPinOffsets[idx] else Offset(x + width, y + height / 2f)
  }
}

data class RoutedNetPolyline(
  val points: List<Offset>,
  val targetCellId: String,
  val targetPinName: String,
  val isBackEdge: Boolean,
)

data class RoutedSchematicNet(
  val net: SchematicNet,
  val branches: List<RoutedNetPolyline>,
  val junctionPoints: List<Offset>,
  val labelPosition: Offset,
  val bounds: Rect,
)

data class RoutedSchematicLayout(
  val graph: SchematicNetlistGraph,
  val placedCells: Map<String, PlacedSchematicCell>,
  val routedNets: List<RoutedSchematicNet>,
  val boundsWidth: Float,
  val boundsHeight: Float,
) {
  fun connectedCellIds(cellId: String): Set<String> {
    val result = mutableSetOf(cellId)
    routedNets.forEach { rNet ->
      val drv = rNet.net.driverCellId
      val loads = rNet.net.loadPins.map { it.first }
      if (drv == cellId) {
        result += loads
      } else if (cellId in loads && drv != null) {
        result += drv
      }
    }
    return result
  }

  fun connectedNetNamesForCell(cellId: String): Set<String> {
    return routedNets.filter { rNet ->
      rNet.net.driverCellId == cellId || rNet.net.loadPins.any { it.first == cellId }
    }.map { it.net.name }.toSet()
  }

  fun cellIdsForNet(netName: String): Set<String> {
    val net = graph.netByName[netName] ?: return emptySet()
    val ids = mutableSetOf<String>()
    net.driverCellId?.let { ids += it }
    net.loadPins.forEach { ids += it.first }
    return ids
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

  // Cadence RTL Compiler standard-cell sizing & routing channel geometry
  const val SCHEM_CELL_WIDTH = 136f
  const val SCHEM_PORT_WIDTH = 108f
  const val SCHEM_COL_PITCH = 264f
  const val SCHEM_ROW_GAP = 32f
  const val SCHEM_PADDING = 56f

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

  // ==================== CADENCE ORTHOGONAL SCHEMATIC ENGINE ====================

  fun computeRoutedSchematicLayout(graph: SchematicNetlistGraph): RoutedSchematicLayout {
    if (graph.cells.isEmpty()) {
      return RoutedSchematicLayout(
        graph = graph,
        placedCells = emptyMap(),
        routedNets = emptyList(),
        boundsWidth = 720f,
        boundsHeight = 460f,
      )
    }

    val driverCellByNet = mutableMapOf<String, String>()
    graph.nets.forEach { net ->
      if (net.driverCellId != null) {
        driverCellByNet[net.name] = net.driverCellId
      }
    }
    val consumerCellsByNet = mutableMapOf<String, List<String>>()
    graph.nets.forEach { net ->
      consumerCellsByNet[net.name] = net.loadPins.map { it.first }
    }

    fun cellWidthFor(cell: SchematicCell): Float = when {
      cell.category.isPort -> SCHEM_PORT_WIDTH
      cell.category.isModule -> 158f
      cell.category.isSequential -> 148f
      else -> SCHEM_CELL_WIDTH
    }

    fun cellHeightFor(cell: SchematicCell): Float {
      if (cell.category.isPort) return 32f
      val maxPins = max(cell.inputPins.size, cell.outputPins.size).coerceAtLeast(1)
      return max(46f, 24f + maxPins * 15f)
    }

    val columns = graph.cells.groupBy { it.level }.toSortedMap()
    val columnOrder = columns.values.map { colCells ->
      colCells.sortedWith(
        compareByDescending<SchematicCell> { it.isCriticalPath }
          .thenBy { it.category.isSequential }
          .thenBy { it.id }
      ).toMutableList()
    }.toMutableList()

    // 2-pass barycenter crossing reduction
    val tempY = mutableMapOf<String, Float>()
    fun refreshTempY() {
      columnOrder.forEach { col ->
        var cursorY = SCHEM_PADDING
        col.forEach { c ->
          val h = cellHeightFor(c)
          tempY[c.id] = cursorY + h / 2f
          cursorY += h + SCHEM_ROW_GAP
        }
      }
    }
    refreshTempY()

    repeat(2) {
      // Forward pass: order by average Y of drivers
      for (colIdx in 1 until columnOrder.size) {
        columnOrder[colIdx].sortBy { cell ->
          val driverYs = cell.inputPins.mapNotNull { pin ->
            val drvId = driverCellByNet[pin.netName]
            if (drvId != null && drvId != cell.id) tempY[drvId] else null
          }
          if (driverYs.isNotEmpty()) driverYs.average().toFloat() else tempY[cell.id] ?: 0f
        }
        refreshTempY()
      }
      // Backward pass: order by average Y of loads
      for (colIdx in (columnOrder.size - 2) downTo 0) {
        columnOrder[colIdx].sortBy { cell ->
          val loadYs = cell.outputPins.flatMap { pin ->
            consumerCellsByNet[pin.netName].orEmpty().mapNotNull { lid ->
              if (lid != cell.id) tempY[lid] else null
            }
          }
          if (loadYs.isNotEmpty()) loadYs.average().toFloat() else tempY[cell.id] ?: 0f
        }
        refreshTempY()
      }
    }

    val colTotalHeights = columnOrder.map { col ->
      col.sumOf { cellHeightFor(it).toDouble() }.toFloat() + (col.size - 1).coerceAtLeast(0) * SCHEM_ROW_GAP
    }
    val maxColHeight = colTotalHeights.maxOrNull() ?: 240f

    val placedCells = linkedMapOf<String, PlacedSchematicCell>()
    columnOrder.forEachIndexed { colIdx, colCells ->
      val colH = colTotalHeights[colIdx]
      var cursorY = SCHEM_PADDING + ((maxColHeight - colH) / 2f).coerceAtLeast(0f)
      val colX = SCHEM_PADDING + colIdx * SCHEM_COL_PITCH

      colCells.forEach { cell ->
        val w = cellWidthFor(cell)
        val h = cellHeightFor(cell)
        val x = if (cell.category == SchematicCellCategory.OUTPUT_PORT) {
          colX + (SCHEM_CELL_WIDTH - w) * 0.4f
        } else {
          colX
        }
        val y = cursorY

        val inOffsets = computePinOffsets(
          count = cell.inputPins.size,
          x = x,
          topY = y,
          height = h,
        )
        val outOffsets = computePinOffsets(
          count = cell.outputPins.size,
          x = x + w,
          topY = y,
          height = h,
        )

        placedCells[cell.id] = PlacedSchematicCell(
          cell = cell,
          x = x,
          y = y,
          width = w,
          height = h,
          inputPinOffsets = inOffsets,
          outputPinOffsets = outOffsets,
        )
        cursorY += h + SCHEM_ROW_GAP
      }
    }

    // Channel track allocator per source column interval for clean 90-degree orthogonal routing
    val maxNodeBottom = placedCells.values.maxOfOrNull { it.y + it.height } ?: (SCHEM_PADDING + 120f)
    var backEdgeLaneCounter = 0
    val channelCounters = mutableMapOf<Int, Int>()
    val netsPerChannel = mutableMapOf<Int, Int>()

    // Pre-count nets traversing each channel so tracks space out cleanly
    graph.nets.forEach { net ->
      val drv = net.driverCellId?.let { placedCells[it] } ?: return@forEach
      if (net.loadPins.isEmpty()) return@forEach
      val chKey = ((drv.x + drv.width) / SCHEM_COL_PITCH).toInt()
      netsPerChannel[chKey] = (netsPerChannel[chKey] ?: 0) + 1
    }

    val routedNets = mutableListOf<RoutedSchematicNet>()

    graph.nets.forEach { net ->
      val drvPlaced = net.driverCellId?.let { placedCells[it] } ?: return@forEach
      val drvPin = drvPlaced.outputPinOffset(net.driverPinName.orEmpty())
      if (net.loadPins.isEmpty()) return@forEach

      val chKey = ((drvPlaced.x + drvPlaced.width) / SCHEM_COL_PITCH).toInt()
      val chTotal = (netsPerChannel[chKey] ?: 1).coerceAtLeast(1)
      val trackIdx = channelCounters[chKey] ?: 0
      channelCounters[chKey] = trackIdx + 1

      val channelLeft = drvPlaced.x + drvPlaced.width + 18f
      val channelRight = drvPlaced.x + SCHEM_COL_PITCH - 18f
      val channelSpan = (channelRight - channelLeft).coerceAtLeast(24f)
      val frac = (trackIdx + 1f) / (chTotal + 1f)
      val defaultTrackX = channelLeft + frac * channelSpan

      val branches = mutableListOf<RoutedNetPolyline>()
      val forwardYs = mutableListOf<Float>()
      val junctionPts = mutableListOf<Offset>()
      var hasForwardBranch = false

      net.loadPins.forEach { (loadCellId, loadPinName) ->
        val loadPlaced = placedCells[loadCellId] ?: return@forEach
        val loadPin = loadPlaced.inputPinOffset(loadPinName)
        val isBackEdge = drvPlaced.x + drvPlaced.width >= loadPlaced.x - 10f

        val pts: List<Offset> = if (!isBackEdge) {
          hasForwardBranch = true
          forwardYs += loadPin.y
          val gapLeft = drvPin.x + 14f
          val gapRight = loadPin.x - 14f
          val localTrackX = if (gapRight > gapLeft) {
            defaultTrackX.coerceIn(gapLeft, gapRight)
          } else {
            (drvPin.x + loadPin.x) / 2f
          }
          if (abs(loadPin.y - drvPin.y) < 1.5f && net.loadPins.size == 1) {
            listOf(drvPin, loadPin)
          } else {
            listOf(
              drvPin,
              Offset(localTrackX, drvPin.y),
              Offset(localTrackX, loadPin.y),
              loadPin,
            )
          }
        } else {
          // Back-edge / feedback net: route orthogonally along dedicated bottom return track
          val laneIdx = backEdgeLaneCounter++
          val returnY = maxNodeBottom + 24f + (laneIdx % 12) * 14f + (laneIdx / 12) * 10f
          val outStubX = drvPin.x + 16f + (laneIdx % 4) * 5f
          val inStubX = loadPin.x - 16f - (laneIdx % 4) * 5f
          listOf(
            drvPin,
            Offset(outStubX, drvPin.y),
            Offset(outStubX, returnY),
            Offset(inStubX, returnY),
            Offset(inStubX, loadPin.y),
            loadPin,
          )
        }

        branches += RoutedNetPolyline(
          points = pts,
          targetCellId = loadCellId,
          targetPinName = loadPinName,
          isBackEdge = isBackEdge,
        )
      }

      if (branches.isEmpty()) return@forEach

      // Compute solder junction points where multi-fanout stems split cleanly on the vertical trunk
      if (hasForwardBranch && branches.count { !it.isBackEdge } > 1) {
        val trunkX = branches.firstOrNull { !it.isBackEdge && it.points.size >= 3 }?.points?.get(1)?.x
        if (trunkX != null) {
          val minY = min(drvPin.y, forwardYs.minOrNull() ?: drvPin.y)
          val maxY = max(drvPin.y, forwardYs.maxOrNull() ?: drvPin.y)
          forwardYs.distinct().forEach { fy ->
            if (fy > minY + 1f && fy < maxY - 1f) {
              junctionPts += Offset(trunkX, fy)
            }
          }
          if (drvPin.y > minY + 1f && drvPin.y < maxY - 1f) {
            junctionPts += Offset(trunkX, drvPin.y)
          }
        }
      }

      val allPts = branches.flatMap { it.points }
      val bMinX = allPts.minOf { it.x }
      val bMinY = allPts.minOf { it.y }
      val bMaxX = allPts.maxOf { it.x }
      val bMaxY = allPts.maxOf { it.y }

      val firstBranch = branches.first()
      val labelPos = if (firstBranch.points.size >= 3) {
        val p1 = firstBranch.points[1]
        val p2 = firstBranch.points[2]
        Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f - 8f)
      } else {
        val p0 = firstBranch.points.first()
        val p1 = firstBranch.points.last()
        Offset((p0.x + p1.x) / 2f, p0.y - 8f)
      }

      routedNets += RoutedSchematicNet(
        net = net,
        branches = branches,
        junctionPoints = junctionPts.distinct(),
        labelPosition = labelPos,
        bounds = Rect(bMinX, bMinY, bMaxX, bMaxY),
      )
    }

    val cellsMaxX = placedCells.values.maxOfOrNull { it.x + it.width } ?: (SCHEM_PADDING + SCHEM_CELL_WIDTH)
    val netsMaxX = routedNets.maxOfOrNull { it.bounds.right } ?: cellsMaxX
    val netsMaxY = routedNets.maxOfOrNull { it.bounds.bottom } ?: maxNodeBottom

    val boundsW = (max(cellsMaxX, netsMaxX) + SCHEM_PADDING).coerceAtLeast(720f)
    val boundsH = (max(maxNodeBottom, netsMaxY) + SCHEM_PADDING).coerceAtLeast(460f)

    return RoutedSchematicLayout(
      graph = graph,
      placedCells = placedCells,
      routedNets = routedNets,
      boundsWidth = boundsW,
      boundsHeight = boundsH,
    )
  }

  private fun computePinOffsets(
    count: Int,
    x: Float,
    topY: Float,
    height: Float,
  ): List<Offset> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(Offset(x, topY + height / 2f))
    val span = (height - 18f).coerceAtLeast(10f)
    val startY = topY + (height - span) / 2f
    val step = span / (count - 1).coerceAtLeast(1)
    return List(count) { idx -> Offset(x, startY + idx * step) }
  }

  fun hitTestSchematicCell(
    layout: RoutedSchematicLayout,
    canvasPoint: Offset,
    panOffset: Offset,
    scale: Float,
    touchSlopWorld: Float = 10f,
  ): SchematicCell? {
    if (scale <= 0.001f) return null
    val worldPoint = Offset(
      x = (canvasPoint.x - panOffset.x) / scale,
      y = (canvasPoint.y - panOffset.y) / scale,
    )
    return layout.placedCells.values.firstOrNull { pos ->
      worldPoint.x >= pos.x - touchSlopWorld &&
        worldPoint.x <= pos.x + pos.width + touchSlopWorld &&
        worldPoint.y >= pos.y - touchSlopWorld &&
        worldPoint.y <= pos.y + pos.height + touchSlopWorld
    }?.cell
  }

  fun hitTestSchematicNet(
    layout: RoutedSchematicLayout,
    canvasPoint: Offset,
    panOffset: Offset,
    scale: Float,
    hitSlopWorld: Float = 10f,
  ): SchematicNet? {
    if (scale <= 0.001f) return null
    val wp = Offset(
      x = (canvasPoint.x - panOffset.x) / scale,
      y = (canvasPoint.y - panOffset.y) / scale,
    )
    var bestNet: SchematicNet? = null
    var bestDist = hitSlopWorld

    layout.routedNets.forEach { rNet ->
      if (wp.x < rNet.bounds.left - hitSlopWorld ||
        wp.x > rNet.bounds.right + hitSlopWorld ||
        wp.y < rNet.bounds.top - hitSlopWorld ||
        wp.y > rNet.bounds.bottom + hitSlopWorld
      ) {
        return@forEach
      }
      rNet.branches.forEach { branch ->
        for (i in 0 until branch.points.lastIndex) {
          val d = distancePointToSegment(wp, branch.points[i], branch.points[i + 1])
          if (d < bestDist) {
            bestDist = d
            bestNet = rNet.net
          }
        }
      }
    }
    return bestNet
  }

  private fun distancePointToSegment(p: Offset, a: Offset, b: Offset): Float {
    val vx = b.x - a.x
    val vy = b.y - a.y
    val wx = p.x - a.x
    val wy = p.y - a.y
    val lenSq = vx * vx + vy * vy
    if (lenSq <= 0.0001f) {
      return kotlin.math.hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble()).toFloat()
    }
    val t = ((wx * vx + wy * vy) / lenSq).coerceIn(0f, 1f)
    val projX = a.x + t * vx
    val projY = a.y + t * vy
    return kotlin.math.hypot((p.x - projX).toDouble(), (p.y - projY).toDouble()).toFloat()
  }

  fun computeSchematicFitTransform(
    layout: RoutedSchematicLayout,
    viewportWidth: Float,
    viewportHeight: Float,
  ): Pair<Float, Offset> = computeFitTransformInternal(
    boundsWidth = layout.boundsWidth,
    boundsHeight = layout.boundsHeight,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
  )

  fun applySchematicTransformGesture(
    currentScale: Float,
    currentPan: Offset,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
    layout: RoutedSchematicLayout,
    viewportWidth: Float,
    viewportHeight: Float,
  ): Pair<Float, Offset> = applyTransformInternal(
    currentScale = currentScale,
    currentPan = currentPan,
    centroid = centroid,
    pan = pan,
    zoom = zoom,
    boundsWidth = layout.boundsWidth,
    boundsHeight = layout.boundsHeight,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
  )

  fun computeFocusCellTransform(
    placed: PlacedSchematicCell,
    currentScale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    layout: RoutedSchematicLayout,
  ): Pair<Float, Offset> {
    val targetScale = currentScale.coerceAtLeast(1.15f).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val rawPan = Offset(
      x = viewportWidth / 2f - placed.center.x * targetScale,
      y = viewportHeight / 2f - placed.center.y * targetScale,
    )
    return targetScale to clampPanInternal(rawPan, targetScale, layout.boundsWidth, layout.boundsHeight, viewportWidth, viewportHeight)
  }

  fun isCellInViewport(
    placed: PlacedSchematicCell,
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

  fun computePanForSchematicMinimapPoint(
    minimapTap: Offset,
    minimapWidth: Float,
    minimapHeight: Float,
    layout: RoutedSchematicLayout,
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
    return clampPanInternal(rawPan, scale, layout.boundsWidth, layout.boundsHeight, viewportWidth, viewportHeight)
  }

  // ==================== EXISTING COMPATIBILITY API ====================

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
  ): Pair<Float, Offset> = computeFitTransformInternal(
    boundsWidth = layout.boundsWidth,
    boundsHeight = layout.boundsHeight,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
  )

  fun clampPanOffset(
    pan: Offset,
    scale: Float,
    layout: NetlistGraphLayout,
    viewportWidth: Float,
    viewportHeight: Float,
  ): Offset = clampPanInternal(pan, scale, layout.boundsWidth, layout.boundsHeight, viewportWidth, viewportHeight)

  fun applyTransformGesture(
    currentScale: Float,
    currentPan: Offset,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
    layout: NetlistGraphLayout,
    viewportWidth: Float,
    viewportHeight: Float,
  ): Pair<Float, Offset> = applyTransformInternal(
    currentScale = currentScale,
    currentPan = currentPan,
    centroid = centroid,
    pan = pan,
    zoom = zoom,
    boundsWidth = layout.boundsWidth,
    boundsHeight = layout.boundsHeight,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
  )

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

  private fun computeFitTransformInternal(
    boundsWidth: Float,
    boundsHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
  ): Pair<Float, Offset> {
    if (viewportWidth <= 1f || viewportHeight <= 1f || boundsWidth <= 1f || boundsHeight <= 1f) {
      return 1f to Offset(24f, 24f)
    }
    val margin = 16f
    val usableW = (viewportWidth - margin * 2f).coerceAtLeast(viewportWidth * 0.6f)
    val usableH = (viewportHeight - margin * 2f).coerceAtLeast(viewportHeight * 0.6f)
    val scaleX = usableW / boundsWidth
    val scaleY = usableH / boundsHeight
    val fitScale = min(scaleX, scaleY).coerceIn(MIN_ZOOM, 1.35f)
    val contentW = boundsWidth * fitScale
    val contentH = boundsHeight * fitScale
    val offsetX = ((viewportWidth - contentW) / 2f).coerceAtLeast(margin)
    val offsetY = ((viewportHeight - contentH) / 2f).coerceAtLeast(margin)
    return fitScale to Offset(offsetX, offsetY)
  }

  private fun clampPanInternal(
    pan: Offset,
    scale: Float,
    boundsWidth: Float,
    boundsHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
  ): Offset {
    if (viewportWidth <= 1f || viewportHeight <= 1f) return pan
    val scaledW = boundsWidth * scale
    val scaledH = boundsHeight * scale
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

  private fun applyTransformInternal(
    currentScale: Float,
    currentPan: Offset,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
    boundsWidth: Float,
    boundsHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
  ): Pair<Float, Offset> {
    val safeZoom = if (zoom.isFinite() && zoom > 0f) zoom else 1f
    val newScale = (currentScale * safeZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val zoomFactor = if (currentScale > 0.0001f) newScale / currentScale else 1f
    val rawPan = centroid - (centroid - currentPan) * zoomFactor + pan
    val clampedPan = clampPanInternal(rawPan, newScale, boundsWidth, boundsHeight, viewportWidth, viewportHeight)
    return newScale to clampedPan
  }
}
