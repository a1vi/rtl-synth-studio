package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.NetlistLayoutEngine
import com.example.domain.synth.PlacedSchematicCell
import com.example.domain.synth.RoutedSchematicLayout
import com.example.domain.synth.RoutedSchematicNet
import com.example.domain.synth.SchematicCellCategory
import com.example.domain.synth.StructuralNetlistParser
import com.example.domain.synth.SynthesisReport
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class SchematicToolMode {
  SELECT,
  PAN,
}

object CadenceSchematicPalette {
  val CanvasBlack = Color(0xFF000000)
  val SubtleGridDot = Color(0xFF1A221A)
  val WireGreen = Color(0xFF55CC77)
  val WireSelectedNet = Color(0xFFFFFF44)
  val WireFanInCyan = Color(0xFF33DDFF)
  val WireFanOutGold = Color(0xFFFFCC33)
  val WireCriticalRed = Color(0xFFFF5555)

  val CellOrangeOutline = Color(0xFFFF8822)
  val CellOrangeFill = Color(0xFF211003)
  val CellSeqFill = Color(0xFF2B1605)
  val CellSeqHeader = Color(0xFF3E2008)
  val CellTextAmber = Color(0xFFFFBC75)
  val InstanceTextGray = Color(0xFFC4CAD4)

  val PortInOutline = Color(0xFF44CCEE)
  val PortInFill = Color(0xFF071A20)
  val PortOutOutline = Color(0xFFE8C547)
  val PortOutFill = Color(0xFF1F1A06)

  val PinInAmber = Color(0xFFFFCC44)
  val PinOutGreen = Color(0xFF55CC77)
  val SelectedHalo = Color(0xFFFFFFFF)

  val ChromeBg = Color(0xFF121417)
  val ChromeSurface = Color(0xFF1B1F24)
  val ChromeBorder = Color(0xFF343B43)
  val ChromeText = Color(0xFFE8ECF1)
  val ChromeMuted = Color(0xFF8F98A3)
}

@Stable
class NetlistViewportState {
  var scale by mutableFloatStateOf(1f)
  var panOffset by mutableStateOf(Offset(24f, 24f))
  var viewportSize by mutableStateOf(IntSize.Zero)

  fun screenToWorldCentered(localOffset: Offset): Offset {
    val safeScale = scale.coerceAtLeast(0.1f)
    return Offset(
      x = (localOffset.x - panOffset.x) / safeScale - NetlistLayoutEngine.SCHEM_CELL_WIDTH / 2f,
      y = (localOffset.y - panOffset.y) / safeScale - 30f,
    )
  }

  fun viewportCenterWorld(): Offset {
    val cx = (viewportSize.width.takeIf { it > 0 } ?: 600) / 2f
    val cy = (viewportSize.height.takeIf { it > 0 } ?: 400) / 2f
    return screenToWorldCentered(Offset(cx, cy))
  }
}

@Composable
fun rememberNetlistViewportState(): NetlistViewportState = remember { NetlistViewportState() }

@Composable
fun NetlistCanvasViewer(
  report: SynthesisReport,
  selectedNodeId: String?,
  onSelectNode: (String?) -> Unit,
  modifier: Modifier = Modifier,
  routedLayout: RoutedSchematicLayout? = null,
  selectedNetName: String? = null,
  onSelectNet: (String?) -> Unit = {},
  onDrillSubmodule: (String) -> Unit = {},
  toolMode: SchematicToolMode = SchematicToolMode.SELECT,
  showNetLabels: Boolean = true,
  showCriticalPath: Boolean = true,
  isolateTriggerToken: Int = 0,
  fitTriggerToken: Int = 0,
  zoomDeltaTrigger: Pair<Int, Float> = 0 to 1f,
  manualPositions: Map<String, Offset> = emptyMap(),
  viewportState: NetlistViewportState = rememberNetlistViewportState(),
  dropTargetScreenPos: Offset? = null,
  wireModeActive: Boolean = false,
  wireSourceNodeId: String? = null,
  highlightedPathNodeIds: Set<String> = emptySet(),
  highlightedPathNets: Set<String> = emptySet(),
) {
  val layout = remember(routedLayout, report.netlistVerilog, report.nodes, report.edges, manualPositions) {
    routedLayout ?: run {
      val parsedGraph = StructuralNetlistParser.buildSchematicGraph(report)
      NetlistLayoutEngine.computeRoutedSchematicLayout(parsedGraph)
    }
  }

  val currentOnSelectNode by rememberUpdatedState(onSelectNode)
  val currentOnSelectNet by rememberUpdatedState(onSelectNet)
  val currentOnDrillSubmodule by rememberUpdatedState(onDrillSubmodule)
  val currentToolMode by rememberUpdatedState(toolMode)

  var hasAutoFitted by remember(layout.graph.topModuleName, layout.graph.scopePath, report.targetLibrary, layout.placedCells.size) {
    mutableStateOf(false)
  }
  var hoveredCellId by remember { mutableStateOf<String?>(null) }
  var isolateConnections by remember { mutableStateOf(false) }
  val coroutineScope = rememberCoroutineScope()
  var viewportAnimJob by remember { mutableStateOf<Job?>(null) }

  fun animateViewportTo(targetScale: Float, targetPan: Offset) {
    val startScale = viewportState.scale
    val startPan = viewportState.panOffset
    viewportAnimJob?.cancel()
    viewportAnimJob = coroutineScope.launch {
      animate(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
      ) { progress, _ ->
        viewportState.scale = startScale + (targetScale - startScale) * progress
        viewportState.panOffset = lerp(startPan, targetPan, progress)
      }
    }
  }

  LaunchedEffect(viewportState.viewportSize, layout, hasAutoFitted) {
    val vw = viewportState.viewportSize.width
    val vh = viewportState.viewportSize.height
    if (!hasAutoFitted && vw > 0 && vh > 0) {
      val (fitScale, fitOffset) = NetlistLayoutEngine.computeSchematicFitTransform(
        layout = layout,
        viewportWidth = vw.toFloat(),
        viewportHeight = vh.toFloat(),
      )
      viewportState.scale = fitScale
      viewportState.panOffset = fitOffset
      hasAutoFitted = true
    }
  }

  LaunchedEffect(fitTriggerToken) {
    if (fitTriggerToken > 0 && viewportState.viewportSize.width > 0 && viewportState.viewportSize.height > 0) {
      val (fitScale, fitOffset) = NetlistLayoutEngine.computeSchematicFitTransform(
        layout = layout,
        viewportWidth = viewportState.viewportSize.width.toFloat(),
        viewportHeight = viewportState.viewportSize.height.toFloat(),
      )
      animateViewportTo(fitScale, fitOffset)
    }
  }

  LaunchedEffect(zoomDeltaTrigger.first) {
    if (zoomDeltaTrigger.first > 0) {
      val vw = viewportState.viewportSize.width.takeIf { it > 0 }?.toFloat() ?: 800f
      val vh = viewportState.viewportSize.height.takeIf { it > 0 }?.toFloat() ?: 600f
      val (targetScale, targetPan) = NetlistLayoutEngine.applySchematicTransformGesture(
        currentScale = viewportState.scale,
        currentPan = viewportState.panOffset,
        centroid = Offset(vw / 2f, vh / 2f),
        pan = Offset.Zero,
        zoom = zoomDeltaTrigger.second,
        layout = layout,
        viewportWidth = vw,
        viewportHeight = vh,
      )
      animateViewportTo(targetScale, targetPan)
    }
  }

  LaunchedEffect(isolateTriggerToken) {
    if (isolateTriggerToken > 0) {
      isolateConnections = !isolateConnections
    }
  }

  // Smoothly focus externally selected cell if off-screen
  LaunchedEffect(selectedNodeId, hasAutoFitted) {
    val targetId = selectedNodeId ?: return@LaunchedEffect
    val vw = viewportState.viewportSize.width.toFloat()
    val vh = viewportState.viewportSize.height.toFloat()
    if (!hasAutoFitted || vw <= 1f || vh <= 1f) return@LaunchedEffect
    val placed = layout.placedCells[targetId] ?: return@LaunchedEffect
    val visible = NetlistLayoutEngine.isCellInViewport(
      placed = placed,
      panOffset = viewportState.panOffset,
      scale = viewportState.scale,
      viewportWidth = vw,
      viewportHeight = vh,
      marginPx = 36f,
    )
    if (!visible) {
      val (targetScale, targetPan) = NetlistLayoutEngine.computeFocusCellTransform(
        placed = placed,
        currentScale = viewportState.scale,
        viewportWidth = vw,
        viewportHeight = vh,
        layout = layout,
      )
      animateViewportTo(targetScale, targetPan)
    }
  }

  val connectedCellIds = remember(layout, selectedNodeId, selectedNetName) {
    when {
      selectedNodeId != null -> layout.connectedCellIds(selectedNodeId)
      selectedNetName != null -> layout.cellIdsForNet(selectedNetName)
      else -> emptySet()
    }
  }
  val selectedCellNetNames = remember(layout, selectedNodeId) {
    selectedNodeId?.let { layout.connectedNetNamesForCell(it) }.orEmpty()
  }

  val textMeasurer = rememberTextMeasurer()
  val scale = viewportState.scale
  val panOffset = viewportState.panOffset
  val viewportSize = viewportState.viewportSize

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(CadenceSchematicPalette.CanvasBlack)
      .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape)
      .clipToBounds()
      .onSizeChanged { viewportState.viewportSize = it },
  ) {
    val canvasWidthDp = maxWidth
    val canvasHeightDp = maxHeight
    val useHorizontalZoomStrip = canvasHeightDp < 350.dp
    val showMinimap = layout.placedCells.size >= 3 && canvasWidthDp >= 290.dp && canvasHeightDp >= 180.dp

    Canvas(
      modifier = Modifier
        .fillMaxSize()
        .clipToBounds()
        .testTag("netlist_canvas")
        .pointerInput(layout) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              when (event.type) {
                PointerEventType.Move -> {
                  val pos = event.changes.firstOrNull()?.position
                  hoveredCellId = pos?.let {
                    NetlistLayoutEngine.hitTestSchematicCell(
                      layout = layout,
                      canvasPoint = it,
                      panOffset = viewportState.panOffset,
                      scale = viewportState.scale,
                    )?.id
                  }
                }
                PointerEventType.Scroll -> {
                  val change = event.changes.firstOrNull()
                  val scrollDelta = change?.scrollDelta?.y ?: 0f
                  if (change != null && scrollDelta != 0f) {
                    viewportAnimJob?.cancel()
                    val zoomFactor = if (scrollDelta < 0f) 1.14f else 0.88f
                    val (nextScale, nextPan) = NetlistLayoutEngine.applySchematicTransformGesture(
                      currentScale = viewportState.scale,
                      currentPan = viewportState.panOffset,
                      centroid = change.position,
                      pan = Offset.Zero,
                      zoom = zoomFactor,
                      layout = layout,
                      viewportWidth = viewportState.viewportSize.width.toFloat(),
                      viewportHeight = viewportState.viewportSize.height.toFloat(),
                    )
                    viewportState.scale = nextScale
                    viewportState.panOffset = nextPan
                    change.consume()
                  }
                }
                PointerEventType.Exit -> hoveredCellId = null
                else -> Unit
              }
            }
          }
        }
        .pointerInput(layout) {
          detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
            viewportAnimJob?.cancel()
            val (nextScale, nextPan) = NetlistLayoutEngine.applySchematicTransformGesture(
              currentScale = viewportState.scale,
              currentPan = viewportState.panOffset,
              centroid = centroid,
              pan = pan,
              zoom = zoom,
              layout = layout,
              viewportWidth = viewportState.viewportSize.width.toFloat(),
              viewportHeight = viewportState.viewportSize.height.toFloat(),
            )
            viewportState.scale = nextScale
            viewportState.panOffset = nextPan
          }
        }
        .pointerInput(layout) {
          detectTapGestures(
            onTap = { tapPos ->
              if (currentToolMode == SchematicToolMode.PAN) return@detectTapGestures
              val hitCell = NetlistLayoutEngine.hitTestSchematicCell(
                layout = layout,
                canvasPoint = tapPos,
                panOffset = viewportState.panOffset,
                scale = viewportState.scale,
              )
              if (hitCell != null) {
                currentOnSelectNet(null)
                currentOnSelectNode(hitCell.id)
              } else {
                val hitNet = NetlistLayoutEngine.hitTestSchematicNet(
                  layout = layout,
                  canvasPoint = tapPos,
                  panOffset = viewportState.panOffset,
                  scale = viewportState.scale,
                )
                if (hitNet != null) {
                  currentOnSelectNode(null)
                  currentOnSelectNet(hitNet.name)
                } else {
                  currentOnSelectNode(null)
                  currentOnSelectNet(null)
                }
              }
            },
            onDoubleTap = { tapPos ->
              val vw = viewportState.viewportSize.width.takeIf { it > 0 }?.toFloat() ?: size.width.toFloat()
              val vh = viewportState.viewportSize.height.takeIf { it > 0 }?.toFloat() ?: size.height.toFloat()
              val hitCell = NetlistLayoutEngine.hitTestSchematicCell(
                layout = layout,
                canvasPoint = tapPos,
                panOffset = viewportState.panOffset,
                scale = viewportState.scale,
              )
              if (hitCell != null) {
                val subScope = hitCell.targetSubmoduleScope
                if (subScope != null) {
                  currentOnDrillSubmodule(subScope)
                } else {
                  currentOnSelectNet(null)
                  currentOnSelectNode(hitCell.id)
                  val placed = layout.placedCells[hitCell.id]
                  if (placed != null && vw > 0f && vh > 0f) {
                    val (targetScale, targetPan) = NetlistLayoutEngine.computeFocusCellTransform(
                      placed = placed,
                      currentScale = viewportState.scale,
                      viewportWidth = vw,
                      viewportHeight = vh,
                      layout = layout,
                    )
                    animateViewportTo(targetScale, targetPan)
                  }
                }
              } else if (vw > 0f && vh > 0f) {
                val (fitScale, fitOffset) = NetlistLayoutEngine.computeSchematicFitTransform(
                  layout = layout,
                  viewportWidth = vw,
                  viewportHeight = vh,
                )
                animateViewportTo(fitScale, fitOffset)
              }
            },
          )
        },
    ) {
      // Pure #000000 Cadence Encounter RTL Compiler canvas
      drawRect(color = CadenceSchematicPalette.CanvasBlack)

      // Ultra-subtle EDA snap dot grid when zoomed in (keeps black surface clean)
      if (scale >= 0.72f) {
        drawCadenceDotGrid(
          panOffset = panOffset,
          scale = scale,
          dotColor = CadenceSchematicPalette.SubtleGridDot,
        )
      }

      // Compute visible world rectangle for fast virtualized culling on large netlists
      val safeScale = scale.coerceAtLeast(0.05f)
      val cullMarginWorld = 120f
      val visibleWorld = Rect(
        left = -panOffset.x / safeScale - cullMarginWorld,
        top = -panOffset.y / safeScale - cullMarginWorld,
        right = (size.width - panOffset.x) / safeScale + cullMarginWorld,
        bottom = (size.height - panOffset.y) / safeScale + cullMarginWorld,
      )

      val hasActiveSelection = selectedNodeId != null || selectedNetName != null
      val hasPathHighlight = highlightedPathNodeIds.isNotEmpty() || highlightedPathNets.isNotEmpty()
      val focusActive = isolateConnections && hasActiveSelection

      // 1. Draw orthogonal 90-degree green wires (#55CC77) + solder junctions
      layout.routedNets.forEach { routedNet ->
        if (!routedNet.bounds.overlaps(visibleWorld)) return@forEach

        val net = routedNet.net
        val isNetSelected = selectedNetName != null && net.name == selectedNetName
        val isDriverOfSelectedCell = selectedNodeId != null && net.driverCellId == selectedNodeId
        val isLoadOfSelectedCell = selectedNodeId != null && net.loadPins.any { it.first == selectedNodeId }
        val isCellConnected = isDriverOfSelectedCell || isLoadOfSelectedCell
        val isInHighlightedPath = hasPathHighlight && (
          net.name in highlightedPathNets ||
            (net.driverCellId in highlightedPathNodeIds && net.loadPins.any { it.first in highlightedPathNodeIds })
          )
        val isHighlighted = isNetSelected || isCellConnected || isInHighlightedPath

        val alphaMult = when {
          focusActive -> when {
            isHighlighted -> 1f
            showCriticalPath && net.isCritical -> 0.55f
            else -> 0.22f
          }
          hasPathHighlight && !isHighlighted -> 0.42f
          else -> 1f
        }

        val wireColor = when {
          isNetSelected -> CadenceSchematicPalette.WireSelectedNet
          isInHighlightedPath -> CadenceSchematicPalette.WireFanInCyan
          isLoadOfSelectedCell && isDriverOfSelectedCell -> CadenceSchematicPalette.WireSelectedNet
          isLoadOfSelectedCell -> CadenceSchematicPalette.WireFanInCyan
          isDriverOfSelectedCell -> CadenceSchematicPalette.WireFanOutGold
          showCriticalPath && net.isCritical -> CadenceSchematicPalette.WireCriticalRed
          else -> CadenceSchematicPalette.WireGreen
        }.copy(alpha = alphaMult)

        drawOrthogonalNet(
          routedNet = routedNet,
          panOffset = panOffset,
          scale = scale,
          wireColor = wireColor,
          isHighlighted = isHighlighted,
          isCritical = showCriticalPath && net.isCritical,
          showLabel = showNetLabels && (isHighlighted || scale >= 0.72f),
          textMeasurer = textMeasurer,
        )
      }

      // 2. Draw standard cells (small orange rectangular blocks) & I/O ports
      layout.placedCells.values.forEach { placed ->
        if (!placed.rect.overlaps(visibleWorld)) return@forEach

        val cell = placed.cell
        val isSelected = cell.id == selectedNodeId
        val isWireSource = cell.id == wireSourceNodeId
        val isHovered = cell.id == hoveredCellId
        val isInPathHighlight = cell.id in highlightedPathNodeIds
        val isConnected = isInPathHighlight || cell.id in connectedCellIds ||
          (selectedNodeId != null && (cell.inputPins.any { it.netName in selectedCellNetNames } || cell.outputPins.any { it.netName in selectedCellNetNames }))
        val dimmed = (focusActive && !isConnected && !isSelected && !isWireSource) ||
          (hasPathHighlight && !focusActive && !isInPathHighlight && !isSelected)

        drawCadenceStandardCell(
          placed = placed,
          panOffset = panOffset,
          scale = scale,
          isSelected = isSelected || isWireSource,
          isHighlightedNeighbor = (hasActiveSelection || hasPathHighlight) && isConnected && !isSelected,
          isHovered = isHovered,
          dimmed = dimmed,
          showCriticalPath = showCriticalPath,
          textMeasurer = textMeasurer,
        )
      }
    }

    // Compact EDA top-right canvas zoom / fit / center / isolate strip
    Surface(
      color = CadenceSchematicPalette.ChromeBg.copy(alpha = 0.90f),
      shape = RectangleShape,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(8.dp)
        .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape)
        .clipToBounds(),
    ) {
      val zoomButtons: @Composable () -> Unit = {
        IconButton(
          onClick = {
            val vw = viewportSize.width.takeIf { it > 0 }?.toFloat() ?: 800f
            val vh = viewportSize.height.takeIf { it > 0 }?.toFloat() ?: 600f
            val (targetScale, targetPan) = NetlistLayoutEngine.applySchematicTransformGesture(
              currentScale = scale,
              currentPan = panOffset,
              centroid = Offset(vw / 2f, vh / 2f),
              pan = Offset.Zero,
              zoom = 1.28f,
              layout = layout,
              viewportWidth = vw,
              viewportHeight = vh,
            )
            animateViewportTo(targetScale, targetPan)
          },
          modifier = Modifier.size(48.dp).testTag("netlist_zoom_in_button"),
        ) {
          Icon(Icons.Rounded.Add, contentDescription = "Zoom in schematic", tint = CadenceSchematicPalette.ChromeText)
        }
        IconButton(
          onClick = {
            val vw = viewportSize.width.takeIf { it > 0 }?.toFloat() ?: 800f
            val vh = viewportSize.height.takeIf { it > 0 }?.toFloat() ?: 600f
            val (targetScale, targetPan) = NetlistLayoutEngine.applySchematicTransformGesture(
              currentScale = scale,
              currentPan = panOffset,
              centroid = Offset(vw / 2f, vh / 2f),
              pan = Offset.Zero,
              zoom = 0.78f,
              layout = layout,
              viewportWidth = vw,
              viewportHeight = vh,
            )
            animateViewportTo(targetScale, targetPan)
          },
          modifier = Modifier.size(48.dp).testTag("netlist_zoom_out_button"),
        ) {
          Icon(Icons.Rounded.Remove, contentDescription = "Zoom out schematic", tint = CadenceSchematicPalette.ChromeText)
        }
        IconButton(
          onClick = {
            if (viewportSize.width > 0 && viewportSize.height > 0) {
              val (fitScale, fitOffset) = NetlistLayoutEngine.computeSchematicFitTransform(
                layout = layout,
                viewportWidth = viewportSize.width.toFloat(),
                viewportHeight = viewportSize.height.toFloat(),
              )
              animateViewportTo(fitScale, fitOffset)
            }
          },
          modifier = Modifier.size(48.dp).testTag("netlist_fit_button"),
        ) {
          Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Fit schematic to view", tint = CadenceSchematicPalette.WireGreen)
        }
        if (selectedNodeId != null) {
          IconButton(
            onClick = {
              val placed = layout.placedCells[selectedNodeId]
              val vw = viewportSize.width.toFloat()
              val vh = viewportSize.height.toFloat()
              if (placed != null && vw > 1f && vh > 1f) {
                val (targetScale, targetPan) = NetlistLayoutEngine.computeFocusCellTransform(
                  placed = placed,
                  currentScale = scale,
                  viewportWidth = vw,
                  viewportHeight = vh,
                  layout = layout,
                )
                animateViewportTo(targetScale, targetPan)
              }
            },
            modifier = Modifier.size(48.dp).testTag("netlist_center_selected_button"),
          ) {
            Icon(Icons.Rounded.MyLocation, contentDescription = "Center on selected cell", tint = CadenceSchematicPalette.CellOrangeOutline)
          }
          IconButton(
            onClick = { isolateConnections = !isolateConnections },
            modifier = Modifier.size(48.dp).testTag("netlist_focus_toggle_button"),
          ) {
            Icon(
              Icons.Rounded.FilterCenterFocus,
              contentDescription = if (isolateConnections) "Show all nets" else "Isolate selected connections",
              tint = if (isolateConnections) CadenceSchematicPalette.WireSelectedNet else CadenceSchematicPalette.ChromeMuted,
            )
          }
        }
      }
      if (useHorizontalZoomStrip) {
        Row(verticalAlignment = Alignment.CenterVertically) { zoomButtons() }
      } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { zoomButtons() }
      }
    }

    // Bottom-left compact EDA zoom & legend bar
    val maxPillWidth = if (showMinimap) {
      (canvasWidthDp - 136.dp).coerceAtLeast(110.dp)
    } else {
      (canvasWidthDp - 16.dp).coerceAtLeast(110.dp)
    }
    Surface(
      color = CadenceSchematicPalette.ChromeBg.copy(alpha = 0.90f),
      shape = RectangleShape,
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(8.dp)
        .widthIn(max = maxPillWidth)
        .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape)
        .clipToBounds(),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val hintText = when {
          selectedNetName != null -> "${(scale * 100).roundToInt()}% | NET: $selectedNetName"
          selectedNodeId != null -> "${(scale * 100).roundToInt()}% | ${connectedCellIds.size - 1} linked | ${toolMode.name}"
          else -> "${(scale * 100).roundToInt()}% | ${layout.placedCells.size} cells | ${toolMode.name}"
        }
        Text(
          text = hintText,
          style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = CadenceSchematicPalette.ChromeText),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false).testTag("netlist_zoom_pill"),
        )
        if (maxPillWidth >= 190.dp) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(modifier = Modifier.size(7.dp).background(CadenceSchematicPalette.CellOrangeOutline))
            Text("Cell", style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, color = CadenceSchematicPalette.CellTextAmber), maxLines = 1)
          }
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(modifier = Modifier.size(7.dp).background(CadenceSchematicPalette.WireGreen))
            Text("Net", style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, color = CadenceSchematicPalette.WireGreen), maxLines = 1)
          }
        }
      }
    }

    // Bottom-right Cadence-palette bird's-eye minimap
    if (showMinimap) {
      CadenceMinimapOverlay(
        layout = layout,
        selectedNodeId = selectedNodeId,
        scale = scale,
        panOffset = panOffset,
        viewportSize = viewportSize,
        onJumpToPan = { targetPan ->
          viewportAnimJob?.cancel()
          viewportState.panOffset = targetPan
        },
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(8.dp),
      )
    }
  }
}

@Composable
private fun CadenceMinimapOverlay(
  layout: RoutedSchematicLayout,
  selectedNodeId: String?,
  scale: Float,
  panOffset: Offset,
  viewportSize: IntSize,
  onJumpToPan: (Offset) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = CadenceSchematicPalette.CanvasBlack.copy(alpha = 0.92f),
    shape = RectangleShape,
    modifier = modifier
      .size(width = 112.dp, height = 70.dp)
      .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape)
      .clipToBounds()
      .testTag("netlist_minimap"),
  ) {
    Canvas(
      modifier = Modifier
        .fillMaxSize()
        .padding(3.dp)
        .clipToBounds()
        .pointerInput(layout, scale, viewportSize) {
          detectTapGestures { tapOffset ->
            val targetPan = NetlistLayoutEngine.computePanForSchematicMinimapPoint(
              minimapTap = tapOffset,
              minimapWidth = size.width.toFloat(),
              minimapHeight = size.height.toFloat(),
              layout = layout,
              scale = scale,
              viewportWidth = viewportSize.width.toFloat(),
              viewportHeight = viewportSize.height.toFloat(),
            )
            onJumpToPan(targetPan)
          }
        }
        .pointerInput(layout, scale, viewportSize) {
          detectDragGestures { change, _ ->
            change.consume()
            val targetPan = NetlistLayoutEngine.computePanForSchematicMinimapPoint(
              minimapTap = change.position,
              minimapWidth = size.width.toFloat(),
              minimapHeight = size.height.toFloat(),
              layout = layout,
              scale = scale,
              viewportWidth = viewportSize.width.toFloat(),
              viewportHeight = viewportSize.height.toFloat(),
            )
            onJumpToPan(targetPan)
          }
        },
    ) {
      val mapW = size.width.coerceAtLeast(1f)
      val mapH = size.height.coerceAtLeast(1f)
      val worldW = layout.boundsWidth.coerceAtLeast(1f)
      val worldH = layout.boundsHeight.coerceAtLeast(1f)
      val sx = mapW / worldW
      val sy = mapH / worldH

      // Tiny green net traces
      layout.routedNets.forEach { rNet ->
        rNet.branches.forEach { branch ->
          for (i in 0 until branch.points.lastIndex) {
            val a = branch.points[i]
            val b = branch.points[i + 1]
            drawLine(
              color = CadenceSchematicPalette.WireGreen.copy(alpha = 0.45f),
              start = Offset(a.x * sx, a.y * sy),
              end = Offset(b.x * sx, b.y * sy),
              strokeWidth = 0.7f,
            )
          }
        }
      }

      // Tiny orange cell rectangles
      layout.placedCells.values.forEach { placed ->
        val c = when {
          placed.cell.id == selectedNodeId -> CadenceSchematicPalette.WireSelectedNet
          placed.cell.category == SchematicCellCategory.INPUT_PORT -> CadenceSchematicPalette.PortInOutline
          placed.cell.category == SchematicCellCategory.OUTPUT_PORT -> CadenceSchematicPalette.PortOutOutline
          else -> CadenceSchematicPalette.CellOrangeOutline
        }
        drawRect(
          color = c,
          topLeft = Offset(placed.x * sx, placed.y * sy),
          size = Size((placed.width * sx).coerceAtLeast(3f), (placed.height * sy).coerceAtLeast(2.5f)),
        )
      }

      // Active viewport box
      val safeScale = scale.coerceAtLeast(0.1f)
      val vwWorld = viewportSize.width / safeScale
      val vhWorld = viewportSize.height / safeScale
      val viewLeftWorld = -panOffset.x / safeScale
      val viewTopWorld = -panOffset.y / safeScale

      val rectX = (viewLeftWorld * sx).coerceIn(0f, mapW - 4f)
      val rectY = (viewTopWorld * sy).coerceIn(0f, mapH - 4f)
      val rectW = (vwWorld * sx).coerceIn(6f, mapW - rectX)
      val rectH = (vhWorld * sy).coerceIn(6f, mapH - rectY)

      drawRect(
        color = Color.White.copy(alpha = 0.85f),
        topLeft = Offset(rectX, rectY),
        size = Size(rectW, rectH),
        style = Stroke(width = 1.1f),
      )
    }
  }
}

private fun worldToScreen(world: Offset, panOffset: Offset, scale: Float): Offset =
  Offset(panOffset.x + world.x * scale, panOffset.y + world.y * scale)

private fun DrawScope.drawCadenceDotGrid(
  panOffset: Offset,
  scale: Float,
  dotColor: Color,
) {
  val step = (24.dp.toPx() * scale).coerceAtLeast(18f)
  val startCol = kotlin.math.floor(-panOffset.x / step).toInt()
  val endCol = kotlin.math.ceil((size.width - panOffset.x) / step).toInt()
  val startRow = kotlin.math.floor(-panOffset.y / step).toInt()
  val endRow = kotlin.math.ceil((size.height - panOffset.y) / step).toInt()
  for (c in startCol..endCol) {
    val x = panOffset.x + c * step
    for (r in startRow..endRow) {
      val y = panOffset.y + r * step
      drawRect(color = dotColor, topLeft = Offset(x, y), size = Size(1.3f, 1.3f))
    }
  }
}

private fun DrawScope.drawOrthogonalNet(
  routedNet: RoutedSchematicNet,
  panOffset: Offset,
  scale: Float,
  wireColor: Color,
  isHighlighted: Boolean,
  isCritical: Boolean,
  showLabel: Boolean,
  textMeasurer: TextMeasurer,
) {
  val baseWidth = when {
    isHighlighted -> 2.3f
    routedNet.net.width > 1 -> 1.8f
    isCritical -> 1.6f
    else -> 1.25f
  }
  val strokeW = (baseWidth * scale).coerceIn(0.9f, 4.2f)

  routedNet.branches.forEach { branch ->
    if (branch.points.size < 2) return@forEach
    val path = Path()
    branch.points.forEachIndexed { idx, pt ->
      val sp = worldToScreen(pt, panOffset, scale)
      if (idx == 0) path.moveTo(sp.x, sp.y) else path.lineTo(sp.x, sp.y)
    }
    val dash = if (branch.isBackEdge) {
      PathEffect.dashPathEffect(floatArrayOf(6f * scale, 4f * scale), 0f)
    } else {
      null
    }
    drawPath(
      path = path,
      color = wireColor,
      style = Stroke(width = strokeW, cap = StrokeCap.Square, join = StrokeJoin.Miter, pathEffect = dash),
    )

    // Multi-bit bus diagonal tick on first segment
    if (routedNet.net.width > 1 && branch.points.size >= 2) {
      val p0 = worldToScreen(branch.points[0], panOffset, scale)
      val p1 = worldToScreen(branch.points[1], panOffset, scale)
      val mid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
      val tick = (4.2f * scale).coerceIn(2f, 7f)
      drawLine(
        color = wireColor,
        start = Offset(mid.x - tick * 0.55f, mid.y + tick),
        end = Offset(mid.x + tick * 0.55f, mid.y - tick),
        strokeWidth = (1.3f * scale).coerceIn(0.8f, 2.5f),
      )
    }
  }

  // Crisp solder junction dots at multi-fanout trunk splits
  val junctionR = (2.8f * scale).coerceIn(1.5f, 5f)
  routedNet.junctionPoints.forEach { jp ->
    drawCircle(
      color = wireColor,
      radius = junctionR,
      center = worldToScreen(jp, panOffset, scale),
    )
  }

  // Optional net name label along trunk (pixel-sized relative to world zoom)
  if (showLabel && scale >= 0.45f) {
    val labelScreen = worldToScreen(routedNet.labelPosition, panOffset, scale)
    if (labelScreen.x in -80f..(size.width + 80f) && labelScreen.y in -40f..(size.height + 40f)) {
      val text = if (routedNet.net.width > 1) "${routedNet.net.name}[${routedNet.net.width}]" else routedNet.net.name
      val netLabelPx = (9f * scale).coerceIn(7f, 15f)
      val measured = textMeasurer.measure(
        text = text,
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = netLabelPx.toSp(),
          fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
          color = wireColor,
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = (104f * scale).roundToInt().coerceAtLeast(24)),
      )
      drawRect(
        color = CadenceSchematicPalette.CanvasBlack.copy(alpha = 0.85f),
        topLeft = Offset(labelScreen.x - measured.size.width / 2f - 2f, labelScreen.y - measured.size.height / 2f),
        size = Size(measured.size.width + 4f, measured.size.height.toFloat()),
      )
      drawText(
        textLayoutResult = measured,
        topLeft = Offset(labelScreen.x - measured.size.width / 2f, labelScreen.y - measured.size.height / 2f),
      )
    }
  }
}

private fun DrawScope.drawCadenceStandardCell(
  placed: PlacedSchematicCell,
  panOffset: Offset,
  scale: Float,
  isSelected: Boolean,
  isHighlightedNeighbor: Boolean,
  isHovered: Boolean,
  dimmed: Boolean,
  showCriticalPath: Boolean,
  textMeasurer: TextMeasurer,
) {
  val cell = placed.cell
  val topLeft = worldToScreen(Offset(placed.x, placed.y), panOffset, scale)
  val w = placed.width * scale
  val h = placed.height * scale
  val alpha = if (dimmed) 0.25f else 1f

  val (fillColor, baseOutline, textColor) = when (cell.category) {
    SchematicCellCategory.INPUT_PORT -> Triple(
      CadenceSchematicPalette.PortInFill,
      CadenceSchematicPalette.PortInOutline,
      Color(0xFF88EEFF),
    )
    SchematicCellCategory.OUTPUT_PORT -> Triple(
      CadenceSchematicPalette.PortOutFill,
      CadenceSchematicPalette.PortOutOutline,
      Color(0xFFFFE680),
    )
    SchematicCellCategory.DFF, SchematicCellCategory.REG -> Triple(
      CadenceSchematicPalette.CellSeqFill,
      CadenceSchematicPalette.CellOrangeOutline,
      CadenceSchematicPalette.CellTextAmber,
    )
    else -> Triple(
      CadenceSchematicPalette.CellOrangeFill,
      CadenceSchematicPalette.CellOrangeOutline,
      CadenceSchematicPalette.CellTextAmber,
    )
  }

  val strokeColor = when {
    isSelected -> CadenceSchematicPalette.WireSelectedNet
    isHighlightedNeighbor -> CadenceSchematicPalette.WireFanInCyan
    showCriticalPath && cell.isCriticalPath && !cell.category.isPort -> CadenceSchematicPalette.WireCriticalRed
    isHovered -> Color.White
    else -> baseOutline
  }.copy(alpha = alpha)

  val strokeW = ((if (isSelected) 2.4f else if (isHighlightedNeighbor) 1.8f else 1.4f) * scale).coerceIn(1f, 4f)

  // Sharp rectangular Cadence cell body (zero corner rounding!)
  drawRect(
    color = fillColor.copy(alpha = alpha),
    topLeft = topLeft,
    size = Size(w, h),
  )

  if (cell.category.isSequential && !dimmed) {
    drawRect(
      color = CadenceSchematicPalette.CellSeqHeader.copy(alpha = 0.85f),
      topLeft = topLeft,
      size = Size(w, (h * 0.28f).coerceAtLeast(10f * scale)),
    )
  }

  drawRect(
    color = strokeColor,
    topLeft = topLeft,
    size = Size(w, h),
    style = Stroke(width = strokeW),
  )

  if (cell.category.isModule) {
    val inset = (3f * scale).coerceAtLeast(1.5f)
    drawRect(
      color = strokeColor.copy(alpha = 0.7f),
      topLeft = Offset(topLeft.x + inset, topLeft.y + inset),
      size = Size((w - inset * 2f).coerceAtLeast(2f), (h - inset * 2f).coerceAtLeast(2f)),
      style = Stroke(width = (0.9f * scale).coerceIn(0.6f, 2f)),
    )
  }

  // Selection hairline bracket frame
  if (isSelected) {
    val pad = (4f * scale).coerceAtLeast(2f)
    drawRect(
      color = CadenceSchematicPalette.WireSelectedNet.copy(alpha = 0.85f),
      topLeft = Offset(topLeft.x - pad, topLeft.y - pad),
      size = Size(w + pad * 2f, h + pad * 2f),
      style = Stroke(
        width = (1.2f * scale).coerceIn(0.8f, 2.5f),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f), 0f),
      ),
    )
  }

  // Input pins on left edge
  val pinBoxHalf = (2.4f * scale).coerceIn(1.2f, 4.2f)
  placed.inputPinOffsets.forEachIndexed { idx, pinWorld ->
    val pinScreen = worldToScreen(pinWorld, panOffset, scale)
    val pinObj = cell.inputPins.getOrNull(idx)
    val isClkPin = pinObj?.pinName.equals("CLK", ignoreCase = true) || pinObj?.pinName.equals("GN", ignoreCase = true)

    drawRect(
      color = CadenceSchematicPalette.PinInAmber.copy(alpha = alpha),
      topLeft = Offset(pinScreen.x - pinBoxHalf, pinScreen.y - pinBoxHalf),
      size = Size(pinBoxHalf * 2f, pinBoxHalf * 2f),
    )

    if (isClkPin && cell.category.isSequential) {
      val triW = (6f * scale).coerceIn(3f, 9f)
      val triH = (3.5f * scale).coerceIn(2f, 6f)
      val tri = Path().apply {
        moveTo(pinScreen.x, pinScreen.y - triH)
        lineTo(pinScreen.x + triW, pinScreen.y)
        lineTo(pinScreen.x, pinScreen.y + triH)
      }
      drawPath(path = tri, color = strokeColor, style = Stroke(width = (1.2f * scale).coerceIn(0.8f, 2.2f)))
    }

    val showPinLabels = scale >= 0.85f && h >= 22f
    if (showPinLabels && !cell.category.isPort && pinObj != null) {
      val pinPx = (7.5f * scale).coerceIn(6f, 11f)
      val maxPinW = (w * 0.24f).roundToInt().coerceAtLeast(1)
      val pMeasured = textMeasurer.measure(
        text = pinObj.pinName.take(4),
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = pinPx.toSp(),
          color = Color(0xFF9DE6B0).copy(alpha = alpha),
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxPinW),
      )
      val xOff = if (isClkPin && cell.category.isSequential) 8f * scale else 5f * scale
      drawText(
        textLayoutResult = pMeasured,
        topLeft = Offset(pinScreen.x + xOff, pinScreen.y - pMeasured.size.height / 2f),
      )
    }
  }

  // Output pins on right edge
  placed.outputPinOffsets.forEachIndexed { idx, pinWorld ->
    val pinScreen = worldToScreen(pinWorld, panOffset, scale)
    val pinObj = cell.outputPins.getOrNull(idx)

    drawRect(
      color = CadenceSchematicPalette.PinOutGreen.copy(alpha = alpha),
      topLeft = Offset(pinScreen.x - pinBoxHalf, pinScreen.y - pinBoxHalf),
      size = Size(pinBoxHalf * 2f, pinBoxHalf * 2f),
    )

    val showPinLabels = scale >= 0.85f && h >= 22f
    if (showPinLabels && !cell.category.isPort && pinObj != null) {
      val pinPx = (7.5f * scale).coerceIn(6f, 11f)
      val maxPinW = (w * 0.24f).roundToInt().coerceAtLeast(1)
      val pMeasured = textMeasurer.measure(
        text = pinObj.pinName.take(4),
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = pinPx.toSp(),
          color = Color(0xFF9DE6B0).copy(alpha = alpha),
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxPinW),
      )
      drawText(
        textLayoutResult = pMeasured,
        topLeft = Offset(pinScreen.x - pMeasured.size.width - 5f * scale, pinScreen.y - pMeasured.size.height / 2f),
      )
    }
  }

  // Text annotations (pixel-sized relative to world cell geometry so labels stay strictly inside cell bounds)
  if (scale >= 0.45f && w >= 20f && h >= 14f) {
    // Instance name just above rectangular block
    if (!cell.category.isPort) {
      val instPx = (9f * scale).coerceIn(6.5f, 14f)
      val instLayout = textMeasurer.measure(
        text = cell.instanceName,
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = instPx.toSp(),
          color = CadenceSchematicPalette.InstanceTextGray.copy(alpha = alpha),
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = (w + 28f * scale).roundToInt().coerceAtLeast(16)),
      )
      drawText(
        textLayoutResult = instLayout,
        topLeft = Offset(topLeft.x, topLeft.y - instLayout.size.height - 2f * scale),
      )
    }

    // Center cell type / port name inside rectangle
    val centerLabel = when {
      cell.category.isPort && cell.bitWidth > 1 -> "${cell.instanceName}[${cell.bitWidth}]"
      cell.category.isPort -> cell.instanceName
      else -> cell.cellType
    }
    val typePx = (10f * scale).coerceIn(7f, 15f)
    val pinReserve = if (!cell.category.isPort && scale >= 0.85f) w * 0.44f else 8f * scale
    val centerMaxW = (w - pinReserve).roundToInt().coerceAtLeast(8)
    val typeLayout = textMeasurer.measure(
      text = centerLabel,
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = typePx.toSp(),
        fontWeight = FontWeight.SemiBold,
        color = textColor.copy(alpha = alpha),
      ),
      overflow = TextOverflow.Ellipsis,
      maxLines = 1,
      constraints = Constraints(maxWidth = centerMaxW),
    )
    drawText(
      textLayoutResult = typeLayout,
      topLeft = Offset(
        x = topLeft.x + (w - typeLayout.size.width) / 2f,
        y = topLeft.y + (h - typeLayout.size.height) / 2f,
      ),
    )
  }
}
