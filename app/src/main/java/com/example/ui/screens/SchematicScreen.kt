package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.GateNode
import com.example.domain.synth.GateType
import com.example.domain.synth.NetEdge
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.DraggedPrimitiveOverlay
import com.example.ui.components.LogicPrimitiveItem
import com.example.ui.components.LogicPrimitiveLibrarySidebar
import com.example.ui.components.NetlistCanvasViewer
import com.example.ui.components.rememberNetlistViewportState
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale

private data class ActivePrimitiveDrag(
  val item: LogicPrimitiveItem,
  val pointerInRoot: Offset,
)

@Composable
fun SchematicScreen(
  synthesisResult: SynthesisResult?,
  selectedNodeId: String?,
  onSelectNode: (String?) -> Unit,
  onNotifyCopied: (String) -> Unit,
  modifier: Modifier = Modifier,
  manualPositions: Map<String, Offset> = emptyMap(),
  isManualSchematic: Boolean = false,
  onAddPrimitive: (GateType, Offset?) -> Unit = { _, _ -> },
  onConnectNodes: (String, String) -> Unit = { _, _ -> },
  onDeleteNode: (String) -> Unit = {},
  onStartBlankSchematic: () -> Unit = {},
  onResetSchematic: () -> Unit = {},
) {
  var viewMode by remember { mutableIntStateOf(0) } // 0 = Schematic, 1 = Netlist Verilog
  var libraryExpanded by remember { mutableStateOf(true) }
  var wireModeActive by remember { mutableStateOf(false) }
  var wireSourceNodeId by remember { mutableStateOf<String?>(null) }
  var activeDrag by remember { mutableStateOf<ActivePrimitiveDrag?>(null) }

  val report = (synthesisResult as? SynthesisResult.Success)?.report
  val viewportState = rememberNetlistViewportState()

  fun handleNodeInteraction(hitNodeId: String?) {
    if (!wireModeActive) {
      onSelectNode(hitNodeId)
      return
    }
    if (hitNodeId == null) {
      wireSourceNodeId = null
      return
    }
    val currentSource = wireSourceNodeId
    if (currentSource == null) {
      val clickedNode = report?.nodes?.firstOrNull { it.id == hitNodeId }
      if (clickedNode?.gateType == GateType.OUTPUT_PORT) {
        onNotifyCopied("Output ports cannot drive other gates. Tap an input or logic gate first.")
      } else {
        wireSourceNodeId = hitNodeId
        onSelectNode(hitNodeId)
      }
    } else if (currentSource == hitNodeId) {
      wireSourceNodeId = null
    } else {
      onConnectNodes(currentSource, hitNodeId)
      wireSourceNodeId = null
      wireModeActive = false
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = report?.let { "${it.moduleName}_synth" } ?: "Gate-Level Netlist",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = report?.let {
            val modePrefix = if (isManualSchematic) "Manual · " else ""
            "$modePrefix${it.nodes.size} cells · ${it.edges.size} nets · ${it.targetLibrary.shortName}"
          } ?: "Build from primitives or synthesize Verilog RTL",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      SingleChoiceSegmentedButtonRow {
        SegmentedButton(
          selected = viewMode == 0,
          onClick = { viewMode = 0 },
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          icon = { Icon(Icons.Rounded.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp)) },
          modifier = Modifier.testTag("schematic_mode_button"),
        ) {
          Text("Diagram", style = MaterialTheme.typography.labelMedium)
        }
        SegmentedButton(
          selected = viewMode == 1,
          onClick = { viewMode = 1 },
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          icon = { Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp)) },
          modifier = Modifier.testTag("netlist_mode_button"),
        ) {
          Text("Netlist", style = MaterialTheme.typography.labelMedium)
        }
      }
    }

    if (report == null) {
      EmptySchematicState(onStartBlankSchematic = onStartBlankSchematic)
      return
    }

    if (viewMode == 0) {
      val activeNode = selectedNodeId?.let { id -> report.nodes.firstOrNull { it.id == id } }

      SchematicSummaryBar(
        report = report,
        selectedNodeId = activeNode?.id,
        isManualSchematic = isManualSchematic,
        onResetSchematic = {
          wireModeActive = false
          wireSourceNodeId = null
          onResetSchematic()
        },
        onFocusCriticalPath = {
          val pathIds = report.criticalPath.map { it.nodeId }
          if (pathIds.isNotEmpty()) {
            val currentIndex = activeNode?.id?.let { pathIds.indexOf(it) } ?: -1
            val nextId = if (currentIndex >= 0) {
              pathIds[(currentIndex + 1) % pathIds.size]
            } else {
              pathIds.last()
            }
            onSelectNode(nextId)
          }
        },
      )

      var workspaceTopLeftInRoot by remember { mutableStateOf(Offset.Zero) }
      var canvasBoundsInRoot by remember { mutableStateOf(Rect.Zero) }

      BoxWithConstraints(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .onGloballyPositioned { workspaceTopLeftInRoot = it.positionInRoot() },
      ) {
        val isWide = maxWidth >= 840.dp
        val compactLibrary = maxWidth < 600.dp

        val dropTargetScreenPos = activeDrag?.let { drag ->
          if (canvasBoundsInRoot.contains(drag.pointerInRoot)) {
            drag.pointerInRoot - canvasBoundsInRoot.topLeft
          } else {
            null
          }
        }

        if (isWide) {
          Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            LogicPrimitiveLibrarySidebar(
              expanded = libraryExpanded,
              compactMode = false,
              wireModeActive = wireModeActive,
              onToggleExpanded = { libraryExpanded = !libraryExpanded },
              onToggleWireMode = {
                wireModeActive = !wireModeActive
                wireSourceNodeId = null
              },
              onStartBlankSchematic = {
                wireModeActive = false
                wireSourceNodeId = null
                onStartBlankSchematic()
              },
              onTapPlace = { gateType ->
                val spread = (report.nodes.size % 4) * 26f
                onAddPrimitive(gateType, viewportState.viewportCenterWorld() + Offset(spread, spread))
              },
              onDragStart = { item, rootPos ->
                activeDrag = ActivePrimitiveDrag(item, rootPos)
              },
              onDragMove = { rootPos ->
                activeDrag = activeDrag?.copy(pointerInRoot = rootPos)
              },
              onDragEnd = { item, rootPos ->
                if (canvasBoundsInRoot.contains(rootPos)) {
                  val localDrop = rootPos - canvasBoundsInRoot.topLeft
                  onAddPrimitive(item.gateType, viewportState.screenToWorldCentered(localDrop))
                }
                activeDrag = null
              },
              onDragCancel = { activeDrag = null },
            )

            Box(
              modifier = Modifier
                .weight(1.45f)
                .fillMaxHeight()
                .onGloballyPositioned { canvasBoundsInRoot = it.boundsInRoot() },
            ) {
              NetlistCanvasViewer(
                report = report,
                selectedNodeId = activeNode?.id,
                onSelectNode = ::handleNodeInteraction,
                manualPositions = manualPositions,
                viewportState = viewportState,
                dropTargetScreenPos = dropTargetScreenPos,
                wireModeActive = wireModeActive,
                wireSourceNodeId = wireSourceNodeId,
                modifier = Modifier.fillMaxSize(),
              )
              WireModeGuidanceBanner(
                visible = wireModeActive,
                hasSource = wireSourceNodeId != null,
                onCancel = {
                  wireModeActive = false
                  wireSourceNodeId = null
                },
                modifier = Modifier
                  .align(Alignment.TopCenter)
                  .padding(top = 10.dp),
              )
            }

            Column(
              modifier = Modifier
                .weight(0.95f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              NodeChipSelector(
                nodes = report.nodes,
                selectedNodeId = activeNode?.id,
                onSelectNode = ::handleNodeInteraction,
              )
              if (activeNode != null) {
                NodeInspectorCard(
                  node = activeNode,
                  report = report,
                  onSelectNode = ::handleNodeInteraction,
                  onClearSelection = { onSelectNode(null) },
                  onConnectFromNode = { srcId ->
                    wireModeActive = true
                    wireSourceNodeId = srcId
                  },
                  onDeleteNode = onDeleteNode,
                )
              } else {
                SchematicIdleOverviewCard(
                  report = report,
                  onSelectNode = ::handleNodeInteraction,
                )
              }
            }
          }
        } else {
          Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              LogicPrimitiveLibrarySidebar(
                expanded = libraryExpanded,
                compactMode = compactLibrary,
                wireModeActive = wireModeActive,
                onToggleExpanded = { libraryExpanded = !libraryExpanded },
                onToggleWireMode = {
                  wireModeActive = !wireModeActive
                  wireSourceNodeId = null
                },
                onStartBlankSchematic = {
                  wireModeActive = false
                  wireSourceNodeId = null
                  onStartBlankSchematic()
                },
                onTapPlace = { gateType ->
                  val spread = (report.nodes.size % 4) * 24f
                  onAddPrimitive(gateType, viewportState.viewportCenterWorld() + Offset(spread, spread))
                },
                onDragStart = { item, rootPos ->
                  activeDrag = ActivePrimitiveDrag(item, rootPos)
                },
                onDragMove = { rootPos ->
                  activeDrag = activeDrag?.copy(pointerInRoot = rootPos)
                },
                onDragEnd = { item, rootPos ->
                  if (canvasBoundsInRoot.contains(rootPos)) {
                    val localDrop = rootPos - canvasBoundsInRoot.topLeft
                    onAddPrimitive(item.gateType, viewportState.screenToWorldCentered(localDrop))
                  }
                  activeDrag = null
                },
                onDragCancel = { activeDrag = null },
              )

              Box(
                modifier = Modifier
                  .weight(1f)
                  .fillMaxHeight()
                  .onGloballyPositioned { canvasBoundsInRoot = it.boundsInRoot() },
              ) {
                NetlistCanvasViewer(
                  report = report,
                  selectedNodeId = activeNode?.id,
                  onSelectNode = ::handleNodeInteraction,
                  manualPositions = manualPositions,
                  viewportState = viewportState,
                  dropTargetScreenPos = dropTargetScreenPos,
                  wireModeActive = wireModeActive,
                  wireSourceNodeId = wireSourceNodeId,
                  modifier = Modifier.fillMaxSize(),
                )
                WireModeGuidanceBanner(
                  visible = wireModeActive,
                  hasSource = wireSourceNodeId != null,
                  onCancel = {
                    wireModeActive = false
                    wireSourceNodeId = null
                  },
                  modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp),
                )
              }
            }

            NodeChipSelector(
              nodes = report.nodes,
              selectedNodeId = activeNode?.id,
              onSelectNode = ::handleNodeInteraction,
            )
            AnimatedVisibility(visible = activeNode != null) {
              if (activeNode != null) {
                NodeInspectorCard(
                  node = activeNode,
                  report = report,
                  onSelectNode = ::handleNodeInteraction,
                  onClearSelection = { onSelectNode(null) },
                  onConnectFromNode = { srcId ->
                    wireModeActive = true
                    wireSourceNodeId = srcId
                  },
                  onDeleteNode = onDeleteNode,
                )
              }
            }
          }
        }

        // Floating gate ghost rendered above the sidebar + canvas during drag-and-drop
        val dragging = activeDrag
        if (dragging != null) {
          DraggedPrimitiveOverlay(
            item = dragging.item,
            localPointerOffset = dragging.pointerInRoot - workspaceTopLeftInRoot,
            canDropOnCanvas = canvasBoundsInRoot.contains(dragging.pointerInRoot),
          )
        }
      }
    } else {
      StructuralNetlistCard(
        netlist = report.netlistVerilog,
        onCopy = {
          onNotifyCopied("Copied structural Verilog netlist to clipboard.")
        },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun SchematicSummaryBar(
  report: SynthesisReport,
  selectedNodeId: String?,
  isManualSchematic: Boolean,
  onResetSchematic: () -> Unit,
  onFocusCriticalPath: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .testTag("schematic_summary_bar"),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SummaryTelemetryPill(
      label = "Fmax",
      value = "${String.format(Locale.US, "%.1f", report.fmaxMhz)} MHz",
      accent = MaterialTheme.colorScheme.primary,
    )
    SummaryTelemetryPill(
      label = "WNS",
      value = "${if (report.wnsNs >= 0) "+" else ""}${String.format(Locale.US, "%.2f", report.wnsNs)} ns",
      accent = if (report.wnsNs < 0.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
    )
    SummaryTelemetryPill(
      label = "Area",
      value = "${String.format(Locale.US, "%.1f", report.totalAreaUm2)} µm²",
      accent = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SummaryTelemetryPill(
      label = "Power",
      value = "${String.format(Locale.US, "%.2f", report.totalPowerMw)} mW",
      accent = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (report.criticalPath.isNotEmpty()) {
      val onCritical = report.criticalPath.any { it.nodeId == selectedNodeId }
      AssistChip(
        onClick = onFocusCriticalPath,
        leadingIcon = {
          Icon(
            imageVector = Icons.Rounded.Bolt,
            contentDescription = "Focus critical path gate",
            modifier = Modifier.size(16.dp),
          )
        },
        label = {
          Text(
            text = if (onCritical) "Next Critical Gate" else "Critical Path (${String.format(Locale.US, "%.2f", report.criticalPathDelayNs)}ns)",
            style = MaterialTheme.typography.labelSmall,
          )
        },
        colors = AssistChipDefaults.assistChipColors(
          containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
          labelColor = MaterialTheme.colorScheme.secondary,
          leadingIconContentColor = MaterialTheme.colorScheme.secondary,
        ),
        modifier = Modifier.testTag("focus_critical_path_chip"),
      )
    }
    if (isManualSchematic) {
      AssistChip(
        onClick = onResetSchematic,
        leadingIcon = {
          Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = "Reset schematic to synthesized RTL",
            modifier = Modifier.size(16.dp),
          )
        },
        label = {
          Text("Reset to RTL", style = MaterialTheme.typography.labelSmall)
        },
        modifier = Modifier.testTag("reset_schematic_button"),
      )
    }
  }
}

@Composable
private fun SummaryTelemetryPill(
  label: String,
  value: String,
  accent: Color,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.small,
    modifier = Modifier.border(
      width = 1.dp,
      color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
      shape = MaterialTheme.shapes.small,
    ),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = value,
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          color = accent,
        ),
      )
    }
  }
}

@Composable
private fun NodeChipSelector(
  nodes: List<GateNode>,
  selectedNodeId: String?,
  onSelectNode: (String?) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    nodes.forEach { node ->
      val selected = node.id == selectedNodeId
      val dotColor = when {
        node.isCriticalPath -> MaterialTheme.colorScheme.secondary
        node.gateType.isSequential -> MaterialTheme.colorScheme.tertiary
        node.gateType.isPort -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
      }
      FilterChip(
        selected = selected,
        onClick = { onSelectNode(if (selected) null else node.id) },
        leadingIcon = {
          Box(
            modifier = Modifier
              .size(8.dp)
              .background(dotColor, CircleShape),
          )
        },
        label = {
          Text(
            text = if (node.gateType.isPort) "${node.gateType.shortLabel}:${node.outputNet}" else node.instanceName,
            style = MaterialTheme.typography.labelSmall,
          )
        },
        colors = FilterChipDefaults.filterChipColors(
          selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        ),
        modifier = Modifier.testTag("node_chip_${node.id}"),
      )
    }
  }
}

@Composable
private fun SchematicIdleOverviewCard(
  report: SynthesisReport,
  onSelectNode: (String?) -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.medium),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = "Critical Path Trace",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "Drag primitives from the Logic Library onto the canvas, use Wire mode to connect pins, or tap a gate below to inspect timing.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      report.criticalPath.take(6).forEach { step ->
        Surface(
          onClick = { onSelectNode(step.nodeId) },
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "${step.instanceName} (${step.cellName})",
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
              ),
              modifier = Modifier.weight(1f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "${String.format(Locale.US, "%.3f", step.arrivalNs)}ns",
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
              ),
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeInspectorCard(
  node: GateNode,
  report: SynthesisReport,
  onSelectNode: (String?) -> Unit,
  onClearSelection: () -> Unit,
  onConnectFromNode: (String) -> Unit,
  onDeleteNode: (String) -> Unit,
) {
  val nodeMap = remember(report.nodes) { report.nodes.associateBy { it.id } }
  val fanInEdges: List<NetEdge> = remember(report.edges, node.id) {
    report.edges.filter { it.toNodeId == node.id }
  }
  val fanOutEdges: List<NetEdge> = remember(report.edges, node.id) {
    report.edges.filter { it.fromNodeId == node.id }
  }

  val (categoryLabel, categoryBg, categoryFg) = when {
    node.gateType.isSequential -> Triple(
      "SEQ",
      MaterialTheme.colorScheme.tertiaryContainer,
      MaterialTheme.colorScheme.tertiary,
    )
    node.gateType.isPort -> Triple(
      "PORT",
      MaterialTheme.colorScheme.primaryContainer,
      MaterialTheme.colorScheme.primary,
    )
    else -> Triple(
      "COMB",
      MaterialTheme.colorScheme.surfaceVariant,
      MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier
      .fillMaxWidth()
      .border(
        width = 1.dp,
        color = if (node.isCriticalPath) {
          MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
        } else {
          MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
        },
        shape = MaterialTheme.shapes.medium,
      )
      .testTag("node_inspector_card"),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Surface(color = categoryBg, shape = MaterialTheme.shapes.extraSmall) {
              Text(
                text = categoryLabel,
                style = MaterialTheme.typography.labelSmall,
                color = categoryFg,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
            Text(
              text = "${node.instanceName} (${node.cellName})",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Text(
            text = "Stage L${node.level} · Output ${node.outputNet}[${node.bitWidth}b]",
            style = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 11.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
          )
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          if (node.isCriticalPath) {
            Surface(
              color = MaterialTheme.colorScheme.secondaryContainer,
              shape = MaterialTheme.shapes.extraSmall,
            ) {
              Text(
                text = "CRITICAL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
          IconButton(
            onClick = { onDeleteNode(node.id) },
            modifier = Modifier
              .size(48.dp)
              .testTag("delete_node_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.DeleteOutline,
              contentDescription = "Remove gate from schematic",
              tint = MaterialTheme.colorScheme.error,
            )
          }
          IconButton(
            onClick = onClearSelection,
            modifier = Modifier
              .size(48.dp)
              .testTag("clear_node_selection_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = "Clear selected gate",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        MetricBadge("Delay", "${String.format(Locale.US, "%.3f", node.delayNs)}ns")
        MetricBadge("Arrival", "${String.format(Locale.US, "%.3f", node.arrivalNs)}ns")
        MetricBadge(
          label = "Slack",
          value = "${String.format(Locale.US, "%.3f", node.slackNs)}ns",
          highlightError = node.slackNs < 0.0,
        )
        MetricBadge("Area", "${String.format(Locale.US, "%.2f", node.areaUm2)}µm²")
        MetricBadge("Power", "${String.format(Locale.US, "%.1f", node.powerUw)}µW")
      }

      if (node.gateType != GateType.OUTPUT_PORT) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          AssistChip(
            onClick = { onConnectFromNode(node.id) },
            leadingIcon = {
              Icon(
                imageVector = Icons.Rounded.Cable,
                contentDescription = "Connect output wire",
                modifier = Modifier.size(16.dp),
              )
            },
            label = {
              Text(
                text = "Wire ${node.outputNet} → Target",
                style = MaterialTheme.typography.labelSmall,
              )
            },
            colors = AssistChipDefaults.assistChipColors(
              leadingIconContentColor = MaterialTheme.colorScheme.tertiary,
            ),
            modifier = Modifier.testTag("wire_from_selected_button"),
          )
        }
      }

      if (fanInEdges.isNotEmpty() || fanOutEdges.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          if (fanInEdges.isNotEmpty()) {
            Text(
              text = "Upstream Drivers (${fanInEdges.size})",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              fanInEdges.forEach { edge ->
                val driver = nodeMap[edge.fromNodeId]
                val label = "${edge.netName} ← ${driver?.instanceName ?: edge.fromNodeId}"
                AssistChip(
                  onClick = { onSelectNode(edge.fromNodeId) },
                  leadingIcon = {
                    Icon(
                      imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                      contentDescription = "Inspect upstream driver",
                      modifier = Modifier.size(14.dp),
                    )
                  },
                  label = {
                    Text(
                      text = label,
                      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 11.sp),
                    )
                  },
                  colors = AssistChipDefaults.assistChipColors(
                    leadingIconContentColor = MaterialTheme.colorScheme.primary,
                  ),
                )
              }
            }
          }

          if (fanOutEdges.isNotEmpty()) {
            Text(
              text = "Downstream Loads (${fanOutEdges.size})",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              fanOutEdges.forEach { edge ->
                val load = nodeMap[edge.toNodeId]
                val label = "${edge.netName} → ${load?.instanceName ?: edge.toNodeId}"
                AssistChip(
                  onClick = { onSelectNode(edge.toNodeId) },
                  leadingIcon = {
                    Icon(
                      imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                      contentDescription = "Inspect downstream load",
                      modifier = Modifier.size(14.dp),
                    )
                  },
                  label = {
                    Text(
                      text = label,
                      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 11.sp),
                    )
                  },
                  colors = AssistChipDefaults.assistChipColors(
                    leadingIconContentColor = MaterialTheme.colorScheme.tertiary,
                  ),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MetricBadge(
  label: String,
  value: String,
  highlightError: Boolean = false,
) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = when {
          highlightError -> MaterialTheme.colorScheme.error
          label == "Slack" -> MaterialTheme.colorScheme.tertiary
          else -> MaterialTheme.colorScheme.onSurface
        },
      ),
    )
  }
}

@Composable
private fun StructuralNetlistCard(
  netlist: String,
  onCopy: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val clipboard = LocalClipboardManager.current
  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.large,
    modifier = modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.large),
  ) {
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
          .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Synthesized Gate-Level Verilog",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(
          onClick = {
            clipboard.setText(AnnotatedString(netlist))
            onCopy()
          },
          modifier = Modifier.size(48.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = "Copy netlist Verilog",
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      }
      Box(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .horizontalScroll(rememberScrollState())
          .padding(14.dp),
      ) {
        Text(
          text = netlist,
          style = TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onBackground,
          ),
        )
      }
    }
  }
}

@Composable
private fun WireModeGuidanceBanner(
  visible: Boolean,
  hasSource: Boolean,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(visible = visible, modifier = modifier) {
    Surface(
      color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
      tonalElevation = 4.dp,
      shadowElevation = 6.dp,
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier
        .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f), MaterialTheme.shapes.medium)
        .testTag("wire_mode_guidance_banner"),
    ) {
      Row(
        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.Cable,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.secondary,
          modifier = Modifier.size(16.dp),
        )
        Text(
          text = if (hasSource) "Now tap target gate to connect" else "Tap source gate to start wire",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        IconButton(
          onClick = onCancel,
          modifier = Modifier
            .size(36.dp)
            .testTag("cancel_wire_mode_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Cancel wire mode",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun EmptySchematicState(
  onStartBlankSchematic: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.large),
  ) {
    Column(
      modifier = Modifier.padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.AccountTree,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(48.dp),
      )
      Text(
        text = "No Valid Netlist Yet",
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = "Synthesize Verilog RTL in the Workbench tab, or start a blank schematic to place logic primitives directly.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      Button(
        onClick = onStartBlankSchematic,
        modifier = Modifier.testTag("empty_start_blank_schematic_button"),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.NoteAdd,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Text(
          text = "Build Blank Schematic",
          modifier = Modifier.padding(start = 8.dp),
        )
      }
    }
  }
}
