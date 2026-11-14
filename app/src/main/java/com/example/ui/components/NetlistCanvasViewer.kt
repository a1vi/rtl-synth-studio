package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.GateNode
import com.example.domain.synth.GateType
import com.example.domain.synth.NetEdge
import com.example.domain.synth.NetlistGraphLayout
import com.example.domain.synth.NetlistLayoutEngine
import com.example.domain.synth.PositionedGateNode
import com.example.domain.synth.SynthesisReport
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Stable
class NetlistViewportState {
  var scale by mutableFloatStateOf(1f)
  var panOffset by mutableStateOf(Offset(24f, 24f))
  var viewportSize by mutableStateOf(IntSize.Zero)

  fun screenToWorldCentered(localOffset: Offset): Offset {
    val safeScale = scale.coerceAtLeast(0.1f)
    return Offset(
      x = (localOffset.x - panOffset.x) / safeScale - NetlistLayoutEngine.NODE_WIDTH / 2f,
      y = (localOffset.y - panOffset.y) / safeScale - NetlistLayoutEngine.NODE_HEIGHT / 2f,
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
  manualPositions: Map<String, Offset> = emptyMap(),
  viewportState: NetlistViewportState = rememberNetlistViewportState(),
  dropTargetScreenPos: Offset? = null,
  wireModeActive: Boolean = false,
  wireSourceNodeId: String? = null,
) {
  val layout = remember(report.nodes, report.edges, manualPositions) {
    NetlistLayoutEngine.computeLayout(report, manualPositions)
  }

  val currentOnSelectNode by rememberUpdatedState(onSelectNode)
  var hasAutoFitted by remember(report.moduleName, report.targetLibrary) {
    mutableStateOf(false)
  }
  var hoveredNodeId by remember { mutableStateOf<String?>(null) }
  var isolateConnections by remember { mutableStateOf(true) }
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
      val (fitScale, fitOffset) = NetlistLayoutEngine.computeFitTransform(
        layout = layout,
        viewportWidth = vw.toFloat(),
        viewportHeight = vh.toFloat(),
      )
      viewportState.scale = fitScale
      viewportState.panOffset = fitOffset
      hasAutoFitted = true
    }
  }

  // Smoothly pan/zoom to bring an externally selected node (e.g., from Critical Path or gate chips) into view
  LaunchedEffect(selectedNodeId, hasAutoFitted) {
    val targetId = selectedNodeId ?: return@LaunchedEffect
    val vw = viewportState.viewportSize.width.toFloat()
    val vh = viewportState.viewportSize.height.toFloat()
    if (!hasAutoFitted || vw <= 1f || vh <= 1f) return@LaunchedEffect
    val placed = layout.positionedNodes[targetId] ?: return@LaunchedEffect
    val visible = NetlistLayoutEngine.isNodeInViewport(
      placed = placed,
      panOffset = viewportState.panOffset,
      scale = viewportState.scale,
      viewportWidth = vw,
      viewportHeight = vh,
      marginPx = 36f,
    )
    if (!visible) {
      val (targetScale, targetPan) = NetlistLayoutEngine.computeFocusNodeTransform(
        placed = placed,
        currentScale = viewportState.scale,
        viewportWidth = vw,
        viewportHeight = vh,
        layout = layout,
      )
      animateViewportTo(targetScale, targetPan)
    }
  }

  val connectedNodeIds = remember(layout, selectedNodeId) {
    selectedNodeId?.let { layout.connectedNodeIds(it) }.orEmpty()
  }
  val fanInEdges = remember(layout, selectedNodeId) {
    selectedNodeId?.let { layout.fanInEdges[it].orEmpty().toSet() }.orEmpty()
  }
  val fanOutEdges = remember(layout, selectedNodeId) {
    selectedNodeId?.let { layout.fanOutEdges[it].orEmpty().toSet() }.orEmpty()
  }

  val eda = LocalEdaColors.current
  val isDark = eda.isDark
  val textMeasurer = rememberTextMeasurer()
  val bgColor = MaterialTheme.colorScheme.background
  val surfaceColor = MaterialTheme.colorScheme.surface
  val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.22f else 0.45f)
  val majorGridColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.42f else 0.72f)
  val primary = MaterialTheme.colorScheme.primary
  val amber = MaterialTheme.colorScheme.secondary
  val emerald = MaterialTheme.colorScheme.tertiary
  val surfaceNode = MaterialTheme.colorScheme.surfaceVariant
  val textColor = MaterialTheme.colorScheme.onSurface
  val subTextColor = MaterialTheme.colorScheme.onSurfaceVariant

  val infiniteTransition = rememberInfiniteTransition(label = "signal_flow")
  val pulsePhase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1800, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "pulse_phase",
  )

  val scale = viewportState.scale
  val panOffset = viewportState.panOffset
  val viewportSize = viewportState.viewportSize

  Surface(
    color = bgColor,
    shape = MaterialTheme.shapes.large,
    modifier = modifier
      .fillMaxSize()
      .border(
        width = if (dropTargetScreenPos != null) 2.dp else 1.dp,
        color = if (dropTargetScreenPos != null) primary else MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.35f else 0.85f),
        shape = MaterialTheme.shapes.large,
      ),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .onSizeChanged { viewportState.viewportSize = it },
    ) {
      Canvas(
        modifier = Modifier
          .fillMaxSize()
          .testTag("netlist_canvas")
          .pointerInput(layout) {
            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent()
                when (event.type) {
                  PointerEventType.Move -> {
                    val pos = event.changes.firstOrNull()?.position
                    hoveredNodeId = pos?.let {
                      NetlistLayoutEngine.hitTestNode(layout, it, viewportState.panOffset, viewportState.scale)?.id
                    }
                  }
                  PointerEventType.Scroll -> {
                    val change = event.changes.firstOrNull()
                    val scrollDelta = change?.scrollDelta?.y ?: 0f
                    if (change != null && scrollDelta != 0f) {
                      viewportAnimJob?.cancel()
                      val zoomFactor = if (scrollDelta < 0f) 1.12f else 0.89f
                      val (nextScale, nextPan) = NetlistLayoutEngine.applyTransformGesture(
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
                  PointerEventType.Exit -> hoveredNodeId = null
                  else -> Unit
                }
              }
            }
          }
          .pointerInput(layout) {
            detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
              viewportAnimJob?.cancel()
              val (nextScale, nextPan) = NetlistLayoutEngine.applyTransformGesture(
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
                val hit = NetlistLayoutEngine.hitTestNode(
                  layout = layout,
                  canvasPoint = tapPos,
                  panOffset = viewportState.panOffset,
                  scale = viewportState.scale,
                )
                currentOnSelectNode(hit?.id)
              },
              onDoubleTap = { tapPos ->
                val vw = viewportState.viewportSize.width.takeIf { it > 0 }?.toFloat() ?: size.width.toFloat()
                val vh = viewportState.viewportSize.height.takeIf { it > 0 }?.toFloat() ?: size.height.toFloat()
                val hit = NetlistLayoutEngine.hitTestNode(
                  layout = layout,
                  canvasPoint = tapPos,
                  panOffset = viewportState.panOffset,
                  scale = viewportState.scale,
                )
                if (hit != null) {
                  currentOnSelectNode(hit.id)
                  val placed = layout.positionedNodes[hit.id]
                  if (placed != null && vw > 0f && vh > 0f) {
                    val (targetScale, targetPan) = NetlistLayoutEngine.computeFocusNodeTransform(
                      placed = placed,
                      currentScale = viewportState.scale,
                      viewportWidth = vw,
                      viewportHeight = vh,
                      layout = layout,
                    )
                    animateViewportTo(targetScale, targetPan)
                  }
                } else if (vw > 0f && vh > 0f) {
                  val (fitScale, fitOffset) = NetlistLayoutEngine.computeFitTransform(
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
        // Atmospheric radial glow behind blueprint grid
        drawRect(
          brush = Brush.radialGradient(
            colors = listOf(
              primary.copy(alpha = if (isDark) 0.08f else 0.04f),
              bgColor,
            ),
            center = Offset(size.width * 0.45f, size.height * 0.45f),
            radius = maxOf(size.width, size.height).coerceAtLeast(200f) * 0.78f,
          ),
        )

        drawBlueprintGrid(
          panOffset = panOffset,
          scale = scale,
          minorColor = gridColor,
          majorColor = majorGridColor,
        )

        drawStageColumnGuides(
          layout = layout,
          panOffset = panOffset,
          scale = scale,
          guideColor = majorGridColor,
          labelColor = subTextColor,
          surfaceBg = surfaceColor,
          textMeasurer = textMeasurer,
        )

        val focusActive = isolateConnections && selectedNodeId != null

        // 1. Draw interconnecting wires & multi-bit bus labels
        report.edges.forEach { edge ->
          val fromPlaced = layout.positionedNodes[edge.fromNodeId] ?: return@forEach
          val toPlaced = layout.positionedNodes[edge.toNodeId] ?: return@forEach

          val isFanIn = edge in fanInEdges
          val isFanOut = edge in fanOutEdges
          val isEdgeHighlighted = isFanIn || isFanOut

          val alphaMultiplier = when {
            !focusActive -> 1f
            isEdgeHighlighted -> 1f
            edge.isCritical -> 0.38f
            else -> 0.16f
          }

          val wireColor = when {
            isFanIn -> primary
            isFanOut -> emerald
            edge.isCritical -> amber
            edge.width > 1 -> primary.copy(alpha = 0.85f)
            else -> subTextColor.copy(alpha = 0.65f)
          }.let { it.copy(alpha = (it.alpha * alphaMultiplier).coerceIn(0.08f, 1f)) }

          val start = worldToScreen(
            world = Offset(fromPlaced.x + fromPlaced.width, fromPlaced.y + fromPlaced.height / 2f),
            panOffset = panOffset,
            scale = scale,
          )
          val end = worldToScreen(
            world = computeTargetPinOffset(toPlaced, edge, layout.fanInEdges[edge.toNodeId].orEmpty()),
            panOffset = panOffset,
            scale = scale,
          )

          drawNetWire(
            edge = edge,
            start = start,
            end = end,
            wireColor = wireColor,
            isHighlighted = isEdgeHighlighted,
            pulsePhase = pulsePhase,
            scale = scale,
            showLabel = (isEdgeHighlighted || edge.isCritical || scale >= 0.95f) && scale >= 0.58f,
            labelBg = surfaceColor,
            textMeasurer = textMeasurer,
          )
        }

        // 2. Draw gate/cell symbols
        layout.positionedNodes.values.forEach { placed ->
          val node = placed.node
          val isSelected = node.id == selectedNodeId
          val isWireSource = node.id == wireSourceNodeId
          val isHovered = node.id == hoveredNodeId
          val isConnected = node.id in connectedNodeIds
          val dimmed = focusActive && !isConnected && !isWireSource

          drawGateComponent(
            placed = placed,
            panOffset = panOffset,
            scale = scale,
            isSelected = isSelected || isWireSource,
            isHovered = isHovered,
            dimmed = dimmed,
            isDark = isDark,
            primary = if (isWireSource) emerald else primary,
            amber = amber,
            emerald = emerald,
            surfaceNode = surfaceNode,
            surfaceBg = surfaceColor,
            textColor = textColor,
            subTextColor = subTextColor,
            textMeasurer = textMeasurer,
          )
        }

        // 3. Draw drag-and-drop target reticle when hovering a primitive over the canvas
        if (dropTargetScreenPos != null) {
          val targetW = NetlistLayoutEngine.NODE_WIDTH * scale
          val targetH = NetlistLayoutEngine.NODE_HEIGHT * scale
          val targetTopLeft = Offset(
            x = dropTargetScreenPos.x - targetW / 2f,
            y = dropTargetScreenPos.y - targetH / 2f,
          )
          val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
          drawRoundRect(
            color = primary.copy(alpha = 0.14f),
            topLeft = targetTopLeft,
            size = Size(targetW, targetH),
            cornerRadius = CornerRadius(10f * scale, 10f * scale),
          )
          drawRoundRect(
            color = primary,
            topLeft = targetTopLeft,
            size = Size(targetW, targetH),
            cornerRadius = CornerRadius(10f * scale, 10f * scale),
            style = Stroke(width = 2f, pathEffect = dash),
          )
        }
      }

      // Top-left compact legend
      Row(
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        NetlistLegendBadge("Critical", amber)
        NetlistLegendBadge("Seq (DFF)", emerald)
        NetlistLegendBadge("Logic / I/O", primary)
      }

      // Top-right Zoom / Fit / Focus toolbar
      Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(10.dp)
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.medium),
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          IconButton(
            onClick = {
              val vw = viewportSize.width.takeIf { it > 0 }?.toFloat() ?: 800f
              val vh = viewportSize.height.takeIf { it > 0 }?.toFloat() ?: 600f
              val (targetScale, targetPan) = NetlistLayoutEngine.applyTransformGesture(
                currentScale = scale,
                currentPan = panOffset,
                centroid = Offset(vw / 2f, vh / 2f),
                pan = Offset.Zero,
                zoom = 1.25f,
                layout = layout,
                viewportWidth = vw,
                viewportHeight = vh,
              )
              animateViewportTo(targetScale, targetPan)
            },
            modifier = Modifier
              .size(48.dp)
              .testTag("netlist_zoom_in_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Add,
              contentDescription = "Zoom in netlist",
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
          IconButton(
            onClick = {
              val vw = viewportSize.width.takeIf { it > 0 }?.toFloat() ?: 800f
              val vh = viewportSize.height.takeIf { it > 0 }?.toFloat() ?: 600f
              val (targetScale, targetPan) = NetlistLayoutEngine.applyTransformGesture(
                currentScale = scale,
                currentPan = panOffset,
                centroid = Offset(vw / 2f, vh / 2f),
                pan = Offset.Zero,
                zoom = 0.80f,
                layout = layout,
                viewportWidth = vw,
                viewportHeight = vh,
              )
              animateViewportTo(targetScale, targetPan)
            },
            modifier = Modifier
              .size(48.dp)
              .testTag("netlist_zoom_out_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Remove,
              contentDescription = "Zoom out netlist",
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
          IconButton(
            onClick = {
              if (viewportSize.width > 0 && viewportSize.height > 0) {
                val (fitScale, fitOffset) = NetlistLayoutEngine.computeFitTransform(
                  layout = layout,
                  viewportWidth = viewportSize.width.toFloat(),
                  viewportHeight = viewportSize.height.toFloat(),
                )
                animateViewportTo(fitScale, fitOffset)
              }
            },
            modifier = Modifier
              .size(48.dp)
              .testTag("netlist_fit_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.CenterFocusStrong,
              contentDescription = "Fit netlist to view",
              tint = MaterialTheme.colorScheme.primary,
            )
          }
          if (selectedNodeId != null) {
            IconButton(
              onClick = {
                val placed = layout.positionedNodes[selectedNodeId]
                val vw = viewportSize.width.toFloat()
                val vh = viewportSize.height.toFloat()
                if (placed != null && vw > 1f && vh > 1f) {
                  val (targetScale, targetPan) = NetlistLayoutEngine.computeFocusNodeTransform(
                    placed = placed,
                    currentScale = scale,
                    viewportWidth = vw,
                    viewportHeight = vh,
                    layout = layout,
                  )
                  animateViewportTo(targetScale, targetPan)
                }
              },
              modifier = Modifier
                .size(48.dp)
                .testTag("netlist_center_selected_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.MyLocation,
                contentDescription = "Center view on selected gate",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            IconButton(
              onClick = { isolateConnections = !isolateConnections },
              modifier = Modifier
                .size(48.dp)
                .testTag("netlist_focus_toggle_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.FilterCenterFocus,
                contentDescription = if (isolateConnections) {
                  "Show all nets at full brightness"
                } else {
                  "Highlight selected gate connections"
                },
                tint = if (isolateConnections) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      // Bottom-left zoom & interaction hint pill
      Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(10.dp)
          .border(
            1.dp,
            if (wireModeActive) emerald.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            MaterialTheme.shapes.small,
          ),
      ) {
        val hintText = when {
          wireModeActive && wireSourceNodeId != null -> "Wire mode · Tap target gate to connect"
          wireModeActive -> "Wire mode · Tap source gate output"
          selectedNodeId != null -> "${(scale * 100).roundToInt()}% · ${connectedNodeIds.size - 1} linked · Pinch/drag"
          else -> "${(scale * 100).roundToInt()}% · Pinch/scroll to zoom · Drag to pan"
        }
        Text(
          text = hintText,
          style = MaterialTheme.typography.labelSmall,
          color = if (wireModeActive) emerald else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("netlist_zoom_pill"),
        )
      }

      // Bottom-right interactive bird's-eye minimap for complex circuits
      val density = LocalDensity.current
      val minViewportForMinimapPx = with(density) { 260.dp.toPx() }
      if (layout.positionedNodes.size >= 3 && viewportSize.width >= minViewportForMinimapPx && viewportSize.height >= minViewportForMinimapPx * 0.7f) {
        NetlistMinimapOverlay(
          layout = layout,
          selectedNodeId = selectedNodeId,
          scale = scale,
          panOffset = panOffset,
          viewportSize = viewportSize,
          primary = primary,
          amber = amber,
          emerald = emerald,
          onJumpToPan = { targetPan ->
            viewportAnimJob?.cancel()
            viewportState.panOffset = targetPan
          },
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(10.dp),
        )
      }
    }
  }
}

@Composable
private fun NetlistMinimapOverlay(
  layout: NetlistGraphLayout,
  selectedNodeId: String?,
  scale: Float,
  panOffset: Offset,
  viewportSize: IntSize,
  primary: Color,
  amber: Color,
  emerald: Color,
  onJumpToPan: (Offset) -> Unit,
  modifier: Modifier = Modifier,
) {
  val outlineColor = MaterialTheme.colorScheme.outline
  val surfaceColor = MaterialTheme.colorScheme.surface

  Surface(
    color = surfaceColor.copy(alpha = 0.88f),
    tonalElevation = 2.dp,
    shape = MaterialTheme.shapes.small,
    modifier = modifier
      .size(width = 116.dp, height = 74.dp)
      .border(1.dp, outlineColor.copy(alpha = 0.35f), MaterialTheme.shapes.small)
      .testTag("netlist_minimap"),
  ) {
    Canvas(
      modifier = Modifier
        .fillMaxSize()
        .padding(4.dp)
        .pointerInput(layout, scale, viewportSize) {
          detectTapGestures { tapOffset ->
            val targetPan = NetlistLayoutEngine.computePanForMinimapPoint(
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
            val targetPan = NetlistLayoutEngine.computePanForMinimapPoint(
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

      // Draw tiny gate blocks
      layout.positionedNodes.values.forEach { placed ->
        val nodeColor = when {
          placed.node.id == selectedNodeId -> primary
          placed.node.isCriticalPath -> amber
          placed.node.gateType.isSequential -> emerald
          else -> outlineColor.copy(alpha = 0.8f)
        }
        drawRoundRect(
          color = nodeColor,
          topLeft = Offset(placed.x * sx, placed.y * sy),
          size = Size((placed.width * sx).coerceAtLeast(4f), (placed.height * sy).coerceAtLeast(3f)),
          cornerRadius = CornerRadius(2f, 2f),
        )
      }

      // Draw current viewport window frame
      val vwWorld = (viewportSize.width / scale.coerceAtLeast(0.1f))
      val vhWorld = (viewportSize.height / scale.coerceAtLeast(0.1f))
      val viewLeftWorld = (-panOffset.x / scale.coerceAtLeast(0.1f))
      val viewTopWorld = (-panOffset.y / scale.coerceAtLeast(0.1f))

      val rectX = (viewLeftWorld * sx).coerceIn(0f, mapW - 4f)
      val rectY = (viewTopWorld * sy).coerceIn(0f, mapH - 4f)
      val rectW = (vwWorld * sx).coerceIn(8f, mapW - rectX)
      val rectH = (vhWorld * sy).coerceIn(8f, mapH - rectY)

      drawRect(
        color = primary.copy(alpha = 0.14f),
        topLeft = Offset(rectX, rectY),
        size = Size(rectW, rectH),
      )
      drawRect(
        color = primary,
        topLeft = Offset(rectX, rectY),
        size = Size(rectW, rectH),
        style = Stroke(width = 1.5f),
      )
    }
  }
}

private fun worldToScreen(world: Offset, panOffset: Offset, scale: Float): Offset =
  Offset(panOffset.x + world.x * scale, panOffset.y + world.y * scale)

private fun computeTargetPinOffset(
  toPlaced: PositionedGateNode,
  edge: NetEdge,
  allIncoming: List<NetEdge>,
): Offset {
  if (allIncoming.size <= 1) {
    return Offset(toPlaced.x, toPlaced.y + toPlaced.height / 2f)
  }
  val idx = allIncoming.indexOf(edge).coerceAtLeast(0)
  val count = allIncoming.size
  val span = toPlaced.height * 0.56f
  val topStart = toPlaced.y + (toPlaced.height - span) / 2f
  val step = span / (count - 1).coerceAtLeast(1)
  return Offset(toPlaced.x, topStart + idx * step)
}

private fun DrawScope.drawBlueprintGrid(
  panOffset: Offset,
  scale: Float,
  minorColor: Color,
  majorColor: Color,
) {
  val minorStep = (24.dp.toPx() * scale).coerceAtLeast(14f)
  val majorEvery = 4

  val startCol = kotlin.math.floor(-panOffset.x / minorStep).toInt()
  val endCol = kotlin.math.ceil((size.width - panOffset.x) / minorStep).toInt()
  for (col in startCol..endCol) {
    val x = panOffset.x + col * minorStep
    val isMajor = col % majorEvery == 0
    drawLine(
      color = if (isMajor) majorColor else minorColor,
      start = Offset(x, 0f),
      end = Offset(x, size.height),
      strokeWidth = if (isMajor) 1.2f else 0.7f,
    )
  }

  val startRow = kotlin.math.floor(-panOffset.y / minorStep).toInt()
  val endRow = kotlin.math.ceil((size.height - panOffset.y) / minorStep).toInt()
  for (row in startRow..endRow) {
    val y = panOffset.y + row * minorStep
    val isMajor = row % majorEvery == 0
    drawLine(
      color = if (isMajor) majorColor else minorColor,
      start = Offset(0f, y),
      end = Offset(size.width, y),
      strokeWidth = if (isMajor) 1.2f else 0.7f,
    )
  }
}

private fun DrawScope.drawStageColumnGuides(
  layout: NetlistGraphLayout,
  panOffset: Offset,
  scale: Float,
  guideColor: Color,
  labelColor: Color,
  surfaceBg: Color,
  textMeasurer: TextMeasurer,
) {
  if (scale < 0.45f || layout.positionedNodes.isEmpty()) return
  val columns = layout.positionedNodes.values.groupBy { it.node.level }.toSortedMap()
  val dash = PathEffect.dashPathEffect(floatArrayOf(6f * scale, 6f * scale), 0f)

  columns.forEach { (level, nodesInCol) ->
    val colWorldX = nodesInCol.first().x + nodesInCol.first().width / 2f
    val screenX = panOffset.x + colWorldX * scale
    if (screenX < -100f || screenX > size.width + 100f) return@forEach

    drawLine(
      color = guideColor.copy(alpha = 0.28f),
      start = Offset(screenX, 0f),
      end = Offset(screenX, size.height),
      strokeWidth = 1f,
      pathEffect = dash,
    )

    val stageTitle = if (level == 0) "STAGE L0 · I/O & FF" else "STAGE L$level"
    val measured = textMeasurer.measure(
      text = stageTitle,
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = (8.5f * scale).coerceIn(7f, 11f).sp,
        fontWeight = FontWeight.SemiBold,
        color = labelColor.copy(alpha = 0.72f),
      ),
    )
    val padX = 7f * scale
    val padY = 2.5f * scale
    val pillW = measured.size.width + padX * 2f
    val pillH = measured.size.height + padY * 2f
    val topY = (panOffset.y + 10f * scale).coerceAtLeast(44.dp.toPx())
    drawRoundRect(
      color = surfaceBg.copy(alpha = 0.90f),
      topLeft = Offset(screenX - pillW / 2f, topY),
      size = Size(pillW, pillH),
      cornerRadius = CornerRadius(4f * scale, 4f * scale),
    )
    drawRoundRect(
      color = guideColor.copy(alpha = 0.45f),
      topLeft = Offset(screenX - pillW / 2f, topY),
      size = Size(pillW, pillH),
      cornerRadius = CornerRadius(4f * scale, 4f * scale),
      style = Stroke(width = (0.8f * scale).coerceIn(0.6f, 1.5f)),
    )
    drawText(
      textLayoutResult = measured,
      topLeft = Offset(screenX - measured.size.width / 2f, topY + padY),
    )
  }
}

private fun DrawScope.drawNetWire(
  edge: NetEdge,
  start: Offset,
  end: Offset,
  wireColor: Color,
  isHighlighted: Boolean,
  pulsePhase: Float,
  scale: Float,
  showLabel: Boolean,
  labelBg: Color,
  textMeasurer: TextMeasurer,
) {
  val isBackEdge = start.x >= end.x - 8f * scale
  val c1: Offset
  val c2: Offset
  if (isBackEdge) {
    val loopDrop = (64f * scale).coerceAtLeast(36f)
    val outerSpread = (58f * scale).coerceAtLeast(28f)
    val detourY = maxOf(start.y, end.y) + loopDrop
    c1 = Offset(start.x + outerSpread, detourY)
    c2 = Offset(end.x - outerSpread, detourY)
  } else {
    val dx = (end.x - start.x).coerceAtLeast(36f * scale)
    c1 = Offset(start.x + dx * 0.45f, start.y)
    c2 = Offset(end.x - dx * 0.45f, end.y)
  }

  val path = Path().apply {
    moveTo(start.x, start.y)
    cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
  }

  val baseStroke = when {
    isHighlighted -> 3.0f
    edge.isCritical -> 2.6f
    edge.width > 1 -> 2.3f
    else -> 1.5f
  }
  val strokeW = (baseStroke * scale).coerceIn(1f, 6f)
  val dashEffect = if (isBackEdge) {
    PathEffect.dashPathEffect(floatArrayOf(10f * scale, 6f * scale), 0f)
  } else {
    null
  }

  if (isHighlighted || edge.isCritical) {
    drawPath(
      path = path,
      color = wireColor.copy(alpha = if (isHighlighted) 0.26f else 0.14f),
      style = Stroke(width = strokeW * 2.5f, cap = StrokeCap.Round, pathEffect = dashEffect),
    )
  }

  drawPath(
    path = path,
    color = wireColor,
    style = Stroke(width = strokeW, cap = StrokeCap.Round, pathEffect = dashEffect),
  )

  // Directional arrowhead at target pin
  val tangent = bezierTangent(start, c1, c2, end, 0.96f)
  val angle = atan2(tangent.y, tangent.x)
  val arrowLen = (7.5f * scale).coerceIn(4f, 12f)
  val arrowSpread = 0.48f
  val arrowPath = Path().apply {
    moveTo(end.x, end.y)
    lineTo(
      end.x - arrowLen * cos(angle - arrowSpread),
      end.y - arrowLen * sin(angle - arrowSpread),
    )
    lineTo(
      end.x - arrowLen * cos(angle + arrowSpread),
      end.y - arrowLen * sin(angle + arrowSpread),
    )
    close()
  }
  drawPath(path = arrowPath, color = wireColor)

  // Multi-bit bus slash tick
  val mid = bezierPoint(start, c1, c2, end, 0.5f)
  if (edge.width > 1) {
    val slashHalf = 5.5f * scale
    drawLine(
      color = wireColor,
      start = Offset(mid.x - slashHalf * 0.6f, mid.y + slashHalf),
      end = Offset(mid.x + slashHalf * 0.6f, mid.y - slashHalf),
      strokeWidth = (1.6f * scale).coerceIn(1f, 3f),
    )
  }

  // Animated signal-flow pulse along active/critical paths
  if (isHighlighted || edge.isCritical) {
    val pulsePt = bezierPoint(start, c1, c2, end, pulsePhase)
    drawCircle(
      color = wireColor,
      radius = (3.8f * scale).coerceIn(2.2f, 6.5f),
      center = pulsePt,
    )
  }

  if (showLabel) {
    val labelText = if (edge.width > 1) "${edge.netName}[${edge.width}]" else edge.netName
    val measured = textMeasurer.measure(
      text = labelText.take(14),
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = (8.5f * scale).coerceIn(7f, 12f).sp,
        fontWeight = FontWeight.Medium,
        color = wireColor,
      ),
    )
    val padX = 5f * scale
    val padY = 2.5f * scale
    val labelW = measured.size.width + padX * 2f
    val labelH = measured.size.height + padY * 2f
    val labelTopLeft = Offset(mid.x - labelW / 2f, mid.y - labelH - 5f * scale)
    drawRoundRect(
      color = labelBg.copy(alpha = 0.94f),
      topLeft = labelTopLeft,
      size = Size(labelW, labelH),
      cornerRadius = CornerRadius(4f * scale, 4f * scale),
    )
    drawRoundRect(
      color = wireColor.copy(alpha = 0.4f),
      topLeft = labelTopLeft,
      size = Size(labelW, labelH),
      cornerRadius = CornerRadius(4f * scale, 4f * scale),
      style = Stroke(width = (0.9f * scale).coerceIn(0.6f, 1.8f)),
    )
    drawText(
      textLayoutResult = measured,
      topLeft = Offset(labelTopLeft.x + padX, labelTopLeft.y + padY),
    )
  }
}

private fun DrawScope.drawGateComponent(
  placed: PositionedGateNode,
  panOffset: Offset,
  scale: Float,
  isSelected: Boolean,
  isHovered: Boolean,
  dimmed: Boolean,
  isDark: Boolean,
  primary: Color,
  amber: Color,
  emerald: Color,
  surfaceNode: Color,
  surfaceBg: Color,
  textColor: Color,
  subTextColor: Color,
  textMeasurer: TextMeasurer,
) {
  val node = placed.node
  val topLeft = worldToScreen(Offset(placed.x, placed.y), panOffset, scale)
  val w = placed.width * scale
  val h = placed.height * scale
  val alpha = if (dimmed) 0.28f else 1f

  val accentColor = when {
    isSelected -> primary
    node.isCriticalPath -> amber
    node.gateType.isSequential -> emerald
    node.gateType.isPort -> primary
    else -> primary.copy(alpha = 0.78f)
  }.copy(alpha = alpha)

  val borderColor = if (isSelected) textColor.copy(alpha = alpha) else accentColor

  val topFill = when {
    isSelected -> primary.copy(alpha = 0.30f)
    node.gateType.isSequential -> emerald.copy(alpha = 0.22f * alpha)
    node.gateType.isPort -> primary.copy(alpha = 0.22f * alpha)
    node.isCriticalPath -> amber.copy(alpha = 0.22f * alpha)
    else -> surfaceNode.copy(alpha = alpha)
  }
  val bottomFill = when {
    isSelected -> primary.copy(alpha = 0.14f)
    node.gateType.isSequential -> surfaceBg.copy(alpha = 0.96f * alpha)
    node.gateType.isPort -> surfaceBg.copy(alpha = 0.96f * alpha)
    node.isCriticalPath -> surfaceBg.copy(alpha = 0.96f * alpha)
    else -> surfaceBg.copy(alpha = 0.92f * alpha)
  }

  // Outer selection / hover halo + EDA corner brackets
  if (isSelected || isHovered) {
    val haloPad = 6f * scale
    drawRoundRect(
      color = primary.copy(alpha = if (isSelected) 0.22f else 0.12f),
      topLeft = Offset(topLeft.x - haloPad, topLeft.y - haloPad),
      size = Size(w + haloPad * 2f, h + haloPad * 2f),
      cornerRadius = CornerRadius(15f * scale, 15f * scale),
    )
    if (isSelected) {
      val bLen = 10f * scale
      val bx0 = topLeft.x - haloPad - 2f * scale
      val by0 = topLeft.y - haloPad - 2f * scale
      val bx1 = topLeft.x + w + haloPad + 2f * scale
      val by1 = topLeft.y + h + haloPad + 2f * scale
      val bracketStroke = (2f * scale).coerceIn(1.2f, 3.5f)
      listOf(
        Offset(bx0, by0) to Offset(bx0 + bLen, by0),
        Offset(bx0, by0) to Offset(bx0, by0 + bLen),
        Offset(bx1, by0) to Offset(bx1 - bLen, by0),
        Offset(bx1, by0) to Offset(bx1, by0 + bLen),
        Offset(bx0, by1) to Offset(bx0 + bLen, by1),
        Offset(bx0, by1) to Offset(bx0, by1 - bLen),
        Offset(bx1, by1) to Offset(bx1 - bLen, by1),
        Offset(bx1, by1) to Offset(bx1, by1 - bLen),
      ).forEach { (p1, p2) ->
        drawLine(color = primary, start = p1, end = p2, strokeWidth = bracketStroke, cap = StrokeCap.Round)
      }
    }
  }

  val shapePath = buildGateSilhouette(node.gateType, topLeft, w, h, scale)
  val strokeWidth = ((if (isSelected) 2.6f else if (node.isCriticalPath) 2.1f else 1.4f) * scale).coerceIn(1f, 5f)

  // Soft elevation shadow beneath each gate
  if (!dimmed) {
    translate(left = 2.5f * scale, top = 4.5f * scale) {
      drawPath(
        path = shapePath,
        color = if (isDark) Color.Black.copy(alpha = 0.22f) else Color(0xFF0F172A).copy(alpha = 0.08f),
      )
    }
  }

  // Gradient-filled gate body & crisp border
  drawPath(
    path = shapePath,
    brush = Brush.verticalGradient(
      colors = listOf(topFill, bottomFill),
      startY = topLeft.y,
      endY = topLeft.y + h,
    ),
  )
  drawPath(
    path = shapePath,
    color = borderColor,
    style = Stroke(width = strokeWidth, join = StrokeJoin.Round, cap = StrokeCap.Round),
  )

  // Gate-specific symbol details (inversion bubbles, XOR arc, DFF clock chevron)
  when (node.gateType) {
    GateType.NAND2, GateType.NOR2, GateType.INV -> {
      val bubbleR = 4.5f * scale
      val bubbleCenter = Offset(topLeft.x + w - bubbleR, topLeft.y + h / 2f)
      drawCircle(color = surfaceBg, radius = bubbleR, center = bubbleCenter)
      drawCircle(
        color = borderColor,
        radius = bubbleR,
        center = bubbleCenter,
        style = Stroke(width = (1.5f * scale).coerceIn(1f, 3f)),
      )
    }
    GateType.XOR2 -> {
      val xorArc = Path().apply {
        moveTo(topLeft.x + 2f * scale, topLeft.y + 4f * scale)
        quadraticTo(
          topLeft.x + 16f * scale,
          topLeft.y + h / 2f,
          topLeft.x + 2f * scale,
          topLeft.y + h - 4f * scale,
        )
      }
      drawPath(
        path = xorArc,
        color = borderColor,
        style = Stroke(width = (1.5f * scale).coerceIn(1f, 3f), cap = StrokeCap.Round),
      )
    }
    GateType.DFF, GateType.DFFR -> {
      val clkTri = Path().apply {
        moveTo(topLeft.x + 2f * scale, topLeft.y + h - 22f * scale)
        lineTo(topLeft.x + 13f * scale, topLeft.y + h - 15f * scale)
        lineTo(topLeft.x + 2f * scale, topLeft.y + h - 8f * scale)
      }
      drawPath(
        path = clkTri,
        color = emerald.copy(alpha = alpha),
        style = Stroke(width = (1.7f * scale).coerceIn(1f, 3f), join = StrokeJoin.Round),
      )
    }
    else -> Unit
  }

  // Input / Output terminal pins + short lead-in stubs
  val stubLen = 6f * scale
  val pinR = (3.2f * scale).coerceIn(1.6f, 5f)
  if (node.gateType != GateType.INPUT_PORT) {
    val inPin = Offset(topLeft.x, topLeft.y + h / 2f)
    drawLine(
      color = borderColor.copy(alpha = 0.7f * alpha),
      start = Offset(inPin.x - stubLen, inPin.y),
      end = inPin,
      strokeWidth = (1.4f * scale).coerceIn(0.8f, 2.5f),
    )
    drawCircle(color = surfaceBg, radius = pinR, center = inPin)
    drawCircle(color = borderColor, radius = pinR, center = inPin, style = Stroke(width = (1.3f * scale).coerceIn(0.8f, 2.5f)))
  }
  if (node.gateType != GateType.OUTPUT_PORT) {
    val outPin = Offset(topLeft.x + w, topLeft.y + h / 2f)
    drawLine(
      color = borderColor.copy(alpha = 0.7f * alpha),
      start = outPin,
      end = Offset(outPin.x + stubLen, outPin.y),
      strokeWidth = (1.4f * scale).coerceIn(0.8f, 2.5f),
    )
    drawCircle(color = borderColor, radius = pinR, center = outPin)
  }

  // Critical-path indicator dot in top-right of cell body
  if (node.isCriticalPath && scale >= 0.45f) {
    drawCircle(
      color = amber.copy(alpha = alpha),
      radius = (3.4f * scale).coerceIn(2f, 5.5f),
      center = Offset(topLeft.x + w - 11f * scale, topLeft.y + 11f * scale),
    )
  }

  // Component text annotations
  if (scale >= 0.45f) {
    val badgeText = gateGlyphTag(node.gateType)
    val labelInsetX = when (node.gateType) {
      GateType.OR2, GateType.NOR2, GateType.XOR2 -> 20f * scale
      GateType.INPUT_PORT -> 11f * scale
      else -> 13f * scale
    }

    val titleLayout = textMeasurer.measure(
      text = node.cellName.take(14),
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = (10f * scale).coerceIn(6.5f, 15f).sp,
        fontWeight = FontWeight.SemiBold,
        color = textColor.copy(alpha = alpha),
      ),
    )
    drawText(
      textLayoutResult = titleLayout,
      topLeft = Offset(topLeft.x + labelInsetX, topLeft.y + 9f * scale),
    )

    val netText = "${node.outputNet}${if (node.bitWidth > 1) "[${node.bitWidth}]" else ""}"
    val netLayout = textMeasurer.measure(
      text = netText.take(15),
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = (9f * scale).coerceIn(6f, 13f).sp,
        color = subTextColor.copy(alpha = alpha),
      ),
    )
    drawText(
      textLayoutResult = netLayout,
      topLeft = Offset(topLeft.x + labelInsetX, topLeft.y + 30f * scale),
    )

    val delayTag = if (scale >= 0.88f && node.delayNs > 0.0) {
      " · ${String.format(java.util.Locale.US, "%.2f", node.delayNs)}ns"
    } else {
      ""
    }
    val footerText = "$badgeText · L${node.level}$delayTag"
    val footerLayout = textMeasurer.measure(
      text = footerText,
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = (8f * scale).coerceIn(5.5f, 11f).sp,
        fontWeight = FontWeight.SemiBold,
        color = accentColor,
      ),
    )
    drawText(
      textLayoutResult = footerLayout,
      topLeft = Offset(topLeft.x + labelInsetX, topLeft.y + h - 18f * scale),
    )

    // D / Q terminal pin glyphs on sequential cells when zoomed in
    if (scale >= 0.88f && node.gateType.isSequential) {
      val pinStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = (7.5f * scale).coerceIn(6f, 10f).sp,
        fontWeight = FontWeight.Bold,
        color = emerald.copy(alpha = alpha),
      )
      val dPin = textMeasurer.measure("D", pinStyle)
      val qPin = textMeasurer.measure("Q", pinStyle)
      drawText(dPin, topLeft = Offset(topLeft.x + 5f * scale, topLeft.y + h / 2f - dPin.size.height / 2f))
      drawText(qPin, topLeft = Offset(topLeft.x + w - qPin.size.width - 5f * scale, topLeft.y + h / 2f - qPin.size.height / 2f))
    }
  }
}

private fun buildGateSilhouette(
  gateType: GateType,
  topLeft: Offset,
  w: Float,
  h: Float,
  scale: Float,
): Path {
  val x = topLeft.x
  val y = topLeft.y
  return when (gateType) {
    GateType.INPUT_PORT -> {
      val tip = 14f * scale
      Path().apply {
        moveTo(x, y + 6f * scale)
        lineTo(x + w - tip, y + 6f * scale)
        lineTo(x + w, y + h / 2f)
        lineTo(x + w - tip, y + h - 6f * scale)
        lineTo(x, y + h - 6f * scale)
        close()
      }
    }
    GateType.OUTPUT_PORT -> {
      val notch = 14f * scale
      Path().apply {
        moveTo(x + notch, y + 6f * scale)
        lineTo(x + w, y + 6f * scale)
        lineTo(x + w, y + h - 6f * scale)
        lineTo(x + notch, y + h - 6f * scale)
        lineTo(x, y + h / 2f)
        close()
      }
    }
    GateType.AND2, GateType.NAND2 -> {
      val bodyRight = if (gateType == GateType.NAND2) x + w - 9f * scale else x + w
      val curveStart = x + w * 0.52f
      Path().apply {
        moveTo(x + 6f * scale, y)
        lineTo(curveStart, y)
        cubicTo(bodyRight, y, bodyRight, y + h, curveStart, y + h)
        lineTo(x + 6f * scale, y + h)
        close()
      }
    }
    GateType.OR2, GateType.NOR2, GateType.XOR2 -> {
      val bodyLeft = if (gateType == GateType.XOR2) x + 9f * scale else x + 2f * scale
      val bodyRight = if (gateType == GateType.NOR2) x + w - 9f * scale else x + w
      Path().apply {
        moveTo(bodyLeft, y)
        lineTo(x + w * 0.45f, y)
        quadraticTo(x + w * 0.85f, y, bodyRight, y + h / 2f)
        quadraticTo(x + w * 0.85f, y + h, x + w * 0.45f, y + h)
        lineTo(bodyLeft, y + h)
        quadraticTo(bodyLeft + 14f * scale, y + h / 2f, bodyLeft, y)
        close()
      }
    }
    GateType.INV, GateType.BUF -> {
      val rightTip = if (gateType == GateType.INV) x + w - 9f * scale else x + w
      Path().apply {
        moveTo(x + 4f * scale, y)
        lineTo(rightTip, y + h / 2f)
        lineTo(x + 4f * scale, y + h)
        close()
      }
    }
    GateType.MUX2 -> {
      val slope = 12f * scale
      Path().apply {
        moveTo(x, y)
        lineTo(x + w, y + slope)
        lineTo(x + w, y + h - slope)
        lineTo(x, y + h)
        close()
      }
    }
    else -> {
      val r = 10f * scale
      Path().apply {
        addRoundRect(
          androidx.compose.ui.geometry.RoundRect(
            left = x,
            top = y,
            right = x + w,
            bottom = y + h,
            cornerRadius = CornerRadius(r, r),
          )
        )
      }
    }
  }
}

private fun gateGlyphTag(type: GateType): String = when (type) {
  GateType.ADD_CELL -> "ALU +"
  GateType.SUB_CELL -> "ALU -"
  GateType.CMP_CELL -> "CMP"
  GateType.MUL_CELL -> "MUL ×"
  else -> type.shortLabel
}

private fun bezierPoint(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
  val u = 1f - t
  val tt = t * t
  val uu = u * u
  val uuu = uu * u
  val ttt = tt * t
  return Offset(
    x = uuu * p0.x + 3f * uu * t * p1.x + 3f * u * tt * p2.x + ttt * p3.x,
    y = uuu * p0.y + 3f * uu * t * p1.y + 3f * u * tt * p2.y + ttt * p3.y,
  )
}

private fun bezierTangent(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
  val u = 1f - t
  return Offset(
    x = 3f * u * u * (p1.x - p0.x) + 6f * u * t * (p2.x - p1.x) + 3f * t * t * (p3.x - p2.x),
    y = 3f * u * u * (p1.y - p0.y) + 6f * u * t * (p2.y - p1.y) + 3f * t * t * (p3.y - p2.y),
  )
}

@Composable
private fun NetlistLegendBadge(label: String, color: Color) {
  Surface(
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    shape = MaterialTheme.shapes.extraSmall,
    modifier = Modifier.border(1.dp, color.copy(alpha = 0.5f), MaterialTheme.shapes.extraSmall),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Box(
        modifier = Modifier
          .size(8.dp)
          .background(color, shape = MaterialTheme.shapes.extraSmall),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
