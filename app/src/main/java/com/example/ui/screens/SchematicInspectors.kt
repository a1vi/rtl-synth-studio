package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AdsClick
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.RoutedSchematicLayout
import com.example.domain.synth.SchematicCell
import com.example.domain.synth.SchematicNet
import com.example.domain.synth.SchematicNetlistGraph
import com.example.domain.synth.SchematicSearchHit
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.CadenceSchematicPalette
import com.example.ui.components.SchematicToolMode
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale

@Composable
internal fun CadenceTopToolbar(
  sidebarOpen: Boolean,
  toolMode: SchematicToolMode,
  showNetLabels: Boolean,
  searchOpen: Boolean,
  canGoBack: Boolean,
  canGoForward: Boolean,
  canGoUpHierarchy: Boolean,
  viewMode: Int,
  hasLayout: Boolean,
  onToggleSidebar: () -> Unit,
  onSetToolMode: (SchematicToolMode) -> Unit,
  onZoomIn: () -> Unit,
  onZoomOut: () -> Unit,
  onFit: () -> Unit,
  onHierarchyUp: () -> Unit,
  onHistoryPrev: () -> Unit,
  onHistoryNext: () -> Unit,
  onToggleSearch: () -> Unit,
  onToggleNetLabels: () -> Unit,
  onExportPng: () -> Unit,
  onExportSvg: () -> Unit,
  onSharePng: () -> Unit,
  onShareSvg: () -> Unit,
  onSetViewMode: (Int) -> Unit,
) {
  var exportMenuExpanded by remember { mutableStateOf(false) }

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .background(CadenceSchematicPalette.ChromeBg)
      .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape)
      .clipToBounds()
      .testTag("schematic_toolbar"),
  ) {
    val isCompactToolbar = maxWidth < 560.dp

    val actionStrip: @Composable (Modifier) -> Unit = { stripModifier ->
      Row(
        modifier = stripModifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        ToolbarIconAction(
          icon = Icons.Rounded.Menu,
          description = "Toggle RTL and hierarchy sidebar",
          active = sidebarOpen,
          enabled = hasLayout,
          onClick = onToggleSidebar,
          testTag = "schematic_sidebar_toggle",
        )
        ToolbarDivider()

        ToolbarIconAction(
          icon = Icons.Rounded.AdsClick,
          description = "Select cell or net tool",
          active = toolMode == SchematicToolMode.SELECT,
          enabled = hasLayout && viewMode == 0,
          onClick = { onSetToolMode(SchematicToolMode.SELECT) },
          testTag = "schematic_tool_select",
        )
        ToolbarIconAction(
          icon = Icons.Rounded.PanTool,
          description = "Pan canvas tool",
          active = toolMode == SchematicToolMode.PAN,
          enabled = hasLayout && viewMode == 0,
          onClick = { onSetToolMode(SchematicToolMode.PAN) },
          testTag = "schematic_tool_pan",
        )
        ToolbarDivider()

        ToolbarIconAction(
          icon = Icons.Rounded.ZoomIn,
          description = "Zoom in schematic",
          active = false,
          enabled = hasLayout && viewMode == 0,
          onClick = onZoomIn,
          testTag = "toolbar_zoom_in",
        )
        ToolbarIconAction(
          icon = Icons.Rounded.ZoomOut,
          description = "Zoom out schematic",
          active = false,
          enabled = hasLayout && viewMode == 0,
          onClick = onZoomOut,
          testTag = "toolbar_zoom_out",
        )
        ToolbarIconAction(
          icon = Icons.Rounded.CenterFocusStrong,
          description = "Fit schematic to screen",
          active = false,
          enabled = hasLayout && viewMode == 0,
          onClick = onFit,
          testTag = "toolbar_fit",
        )
        ToolbarDivider()

        ToolbarIconAction(
          icon = Icons.Rounded.ChevronLeft,
          description = "Previous schematic view",
          active = false,
          enabled = canGoBack,
          onClick = onHistoryPrev,
          testTag = "schematic_history_prev",
        )
        ToolbarIconAction(
          icon = Icons.Rounded.ChevronRight,
          description = "Next schematic view",
          active = false,
          enabled = canGoForward,
          onClick = onHistoryNext,
          testTag = "schematic_history_next",
        )
        ToolbarIconAction(
          icon = Icons.Rounded.ArrowUpward,
          description = "Up one hierarchy level",
          active = canGoUpHierarchy,
          enabled = canGoUpHierarchy,
          onClick = onHierarchyUp,
          testTag = "schematic_hierarchy_up",
        )
        ToolbarDivider()

        ToolbarIconAction(
          icon = Icons.Rounded.Search,
          description = "Search cells and nets",
          active = searchOpen,
          enabled = hasLayout && viewMode == 0,
          onClick = onToggleSearch,
          testTag = "schematic_search_button",
        )
        ToolbarIconAction(
          icon = Icons.AutoMirrored.Rounded.Label,
          description = "Toggle net labels",
          active = showNetLabels,
          enabled = hasLayout && viewMode == 0,
          onClick = onToggleNetLabels,
          testTag = "schematic_net_labels_toggle",
        )

        Box {
          ToolbarIconAction(
            icon = Icons.Rounded.FileDownload,
            description = "Export schematic PNG or SVG",
            active = exportMenuExpanded,
            enabled = hasLayout,
            onClick = { exportMenuExpanded = true },
            testTag = "schematic_export_button",
          )
          DropdownMenu(
            expanded = exportMenuExpanded,
            onDismissRequest = { exportMenuExpanded = false },
            modifier = Modifier.background(CadenceSchematicPalette.ChromeSurface),
          ) {
            DropdownMenuItem(
              text = { Text("Save Schematic PNG (.png)", color = CadenceSchematicPalette.ChromeText, fontFamily = JetBrainsMonoFamily, fontSize = 12.sp) },
              onClick = {
                exportMenuExpanded = false
                onExportPng()
              },
              modifier = Modifier.testTag("export_schematic_png"),
            )
            DropdownMenuItem(
              text = { Text("Save Schematic SVG (.svg)", color = CadenceSchematicPalette.ChromeText, fontFamily = JetBrainsMonoFamily, fontSize = 12.sp) },
              onClick = {
                exportMenuExpanded = false
                onExportSvg()
              },
              modifier = Modifier.testTag("export_schematic_svg"),
            )
            DropdownMenuItem(
              text = { Text("Share PNG Image", color = CadenceSchematicPalette.WireGreen, fontFamily = JetBrainsMonoFamily, fontSize = 12.sp) },
              onClick = {
                exportMenuExpanded = false
                onSharePng()
              },
              modifier = Modifier.testTag("share_schematic_png"),
            )
            DropdownMenuItem(
              text = { Text("Share SVG Vector", color = CadenceSchematicPalette.CellTextAmber, fontFamily = JetBrainsMonoFamily, fontSize = 12.sp) },
              onClick = {
                exportMenuExpanded = false
                onShareSvg()
              },
              modifier = Modifier.testTag("share_schematic_svg"),
            )
          }
        }
      }
    }

    val modeToggle: @Composable () -> Unit = {
      CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.height(34.dp)) {
          SegmentedButton(
            selected = viewMode == 0,
            onClick = { onSetViewMode(0) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = {},
            modifier = Modifier.testTag("schematic_mode_button"),
          ) {
            Text("Diagram", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
          }
          SegmentedButton(
            selected = viewMode == 1,
            onClick = { onSetViewMode(1) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = {},
            modifier = Modifier.testTag("netlist_mode_button"),
          ) {
            Text("Netlist", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
          }
        }
      }
    }

    if (isCompactToolbar) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "NETLIST VIEWER",
            style = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = CadenceSchematicPalette.CellTextAmber,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
          )
          modeToggle()
        }
        HorizontalDivider(color = CadenceSchematicPalette.ChromeBorder)
        actionStrip(
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        )
      }
    } else {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        actionStrip(Modifier.weight(1f))
        ToolbarDivider()
        modeToggle()
      }
    }
  }
}

@Composable
private fun ToolbarIconAction(
  icon: ImageVector,
  description: String,
  active: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  testTag: String,
) {
  IconButton(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier
      .size(44.dp)
      .background(if (active) Color(0xFF281706) else Color.Transparent, RectangleShape)
      .border(
        width = if (active) 1.dp else 0.dp,
        color = if (active) CadenceSchematicPalette.CellOrangeOutline else Color.Transparent,
        shape = RectangleShape,
      )
      .testTag(testTag),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = description,
      tint = when {
        !enabled -> CadenceSchematicPalette.ChromeMuted.copy(alpha = 0.35f)
        active -> CadenceSchematicPalette.CellOrangeOutline
        else -> CadenceSchematicPalette.ChromeText
      },
      modifier = Modifier.size(18.dp),
    )
  }
}

@Composable
private fun ToolbarDivider() {
  Box(
    modifier = Modifier
      .padding(horizontal = 2.dp)
      .width(1.dp)
      .height(22.dp)
      .background(CadenceSchematicPalette.ChromeBorder),
  )
}

@Composable
internal fun HierarchyBreadcrumbBar(
  topModuleName: String,
  activeScopePath: String,
  cellCount: Int,
  netCount: Int,
  onSelectScope: (String) -> Unit,
) {
  val segments = remember(activeScopePath) {
    if (activeScopePath.isBlank()) emptyList() else activeScopePath.split('/').filter { it.isNotBlank() }
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(CadenceSchematicPalette.ChromeSurface)
      .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape)
      .clipToBounds()
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 8.dp, vertical = 4.dp)
      .testTag("schematic_breadcrumb_bar"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      text = "SCOPE:",
      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.ChromeMuted),
    )
    Text(
      text = topModuleName,
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = if (segments.isEmpty()) CadenceSchematicPalette.WireGreen else CadenceSchematicPalette.CellTextAmber,
      ),
      modifier = Modifier
        .clickable { onSelectScope("") }
        .padding(horizontal = 4.dp, vertical = 2.dp),
    )
    var runningScope = ""
    segments.forEachIndexed { idx, seg ->
      runningScope = if (runningScope.isEmpty()) seg else "$runningScope/$seg"
      val target = runningScope
      val isLeaf = idx == segments.lastIndex
      Text(
        text = "/",
        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = CadenceSchematicPalette.ChromeMuted),
      )
      Text(
        text = seg,
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 10.sp,
          fontWeight = if (isLeaf) FontWeight.Bold else FontWeight.Normal,
          color = if (isLeaf) CadenceSchematicPalette.WireGreen else CadenceSchematicPalette.CellTextAmber,
        ),
        modifier = Modifier
          .clickable { onSelectScope(target) }
          .padding(horizontal = 4.dp, vertical = 2.dp),
      )
    }
    Spacer(Modifier.width(8.dp))
    Text(
      text = "[$cellCount inst · $netCount nets]",
      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.ChromeMuted),
    )
  }
}

@Composable
internal fun SchematicSearchPanel(
  query: String,
  results: List<SchematicSearchHit>,
  onQueryChange: (String) -> Unit,
  onPickHit: (SchematicSearchHit) -> Unit,
  onClose: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(CadenceSchematicPalette.ChromeSurface)
      .border(1.dp, CadenceSchematicPalette.CellOrangeOutline, RectangleShape)
      .clipToBounds()
      .padding(8.dp)
      .testTag("schematic_search_panel"),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(Icons.Rounded.Search, contentDescription = null, tint = CadenceSchematicPalette.CellOrangeOutline, modifier = Modifier.size(16.dp))
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        cursorBrush = SolidColor(CadenceSchematicPalette.WireGreen),
        textStyle = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp, color = Color.White),
        decorationBox = { inner ->
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .background(CadenceSchematicPalette.CanvasBlack)
              .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape)
              .padding(horizontal = 8.dp, vertical = 6.dp),
          ) {
            if (query.isEmpty()) {
              Text(
                text = "Search cell instance, std-cell type, or net name...",
                style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 11.sp, color = CadenceSchematicPalette.ChromeMuted),
              )
            }
            inner()
          }
        },
        modifier = Modifier.weight(1f).testTag("schematic_search_input"),
      )
      IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Rounded.Close, contentDescription = "Close search", tint = CadenceSchematicPalette.ChromeText, modifier = Modifier.size(16.dp))
      }
    }

    if (results.isNotEmpty()) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 140.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        results.forEachIndexed { idx, hit ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(CadenceSchematicPalette.CanvasBlack)
              .clickable { onPickHit(hit) }
              .padding(horizontal = 8.dp, vertical = 6.dp)
              .testTag("schematic_search_hit_$idx"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = hit.label,
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (hit is SchematicSearchHit.NetHit) CadenceSchematicPalette.WireGreen else CadenceSchematicPalette.CellTextAmber,
              ),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            Text(
              text = hit.subtitle,
              style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = CadenceSchematicPalette.ChromeMuted),
              maxLines = 1,
            )
          }
        }
      }
    } else if (query.isNotBlank()) {
      Text(
        text = "No matching cells or nets in current scope.",
        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = CadenceSchematicPalette.ChromeMuted),
      )
    }
  }
}

@Composable
internal fun SchematicSummaryBar(
  report: SynthesisReport,
  selectedNodeId: String?,
  onFocusCriticalPath: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clipToBounds()
      .horizontalScroll(rememberScrollState())
      .testTag("schematic_summary_bar"),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SummaryTelemetryPill(
      label = "Fmax",
      value = "${String.format(Locale.US, "%.1f", report.fmaxMhz)} MHz",
      accent = CadenceSchematicPalette.WireGreen,
    )
    SummaryTelemetryPill(
      label = "WNS",
      value = "${if (report.wnsNs >= 0) "+" else ""}${String.format(Locale.US, "%.2f", report.wnsNs)} ns",
      accent = if (report.wnsNs < 0.0) CadenceSchematicPalette.WireCriticalRed else CadenceSchematicPalette.WireGreen,
    )
    SummaryTelemetryPill(
      label = "Area",
      value = "${String.format(Locale.US, "%.1f", report.totalAreaUm2)} µm²",
      accent = CadenceSchematicPalette.CellTextAmber,
    )
    SummaryTelemetryPill(
      label = "Power",
      value = "${String.format(Locale.US, "%.2f", report.totalPowerMw)} mW",
      accent = CadenceSchematicPalette.CellTextAmber,
    )
    if (report.criticalPath.isNotEmpty()) {
      val onCritical = report.criticalPath.any { it.nodeId == selectedNodeId }
      CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        AssistChip(
          onClick = onFocusCriticalPath,
          shape = RectangleShape,
          leadingIcon = {
            Icon(
              imageVector = Icons.Rounded.Bolt,
              contentDescription = "Focus critical path gate",
              modifier = Modifier.size(14.dp),
            )
          },
          label = {
            Text(
              text = if (onCritical) "Next Critical Gate" else "Critical Path (${String.format(Locale.US, "%.2f", report.criticalPathDelayNs)}ns)",
              style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp),
            )
          },
          colors = AssistChipDefaults.assistChipColors(
            containerColor = Color(0xFF2B1010),
            labelColor = CadenceSchematicPalette.WireCriticalRed,
            leadingIconContentColor = CadenceSchematicPalette.WireCriticalRed,
          ),
          modifier = Modifier.height(30.dp).testTag("focus_critical_path_chip"),
        )
      }
    }
  }
}

@Composable
private fun SummaryTelemetryPill(
  label: String,
  value: String,
  accent: Color,
) {
  Row(
    modifier = Modifier
      .background(CadenceSchematicPalette.ChromeSurface, RectangleShape)
      .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape)
      .padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = CadenceSchematicPalette.ChromeMuted),
    )
    Text(
      text = value,
      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = accent),
    )
  }
}

@Composable
internal fun SchematicCellStrip(
  cells: List<SchematicCell>,
  selectedCellId: String?,
  onSelectCell: (String?) -> Unit,
) {
  CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clipToBounds()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      cells.take(40).forEach { cell ->
        val selected = cell.id == selectedCellId
        val dotColor = when {
          cell.isCriticalPath -> CadenceSchematicPalette.WireCriticalRed
          cell.category.isPort -> CadenceSchematicPalette.PortInOutline
          cell.category.isSequential -> CadenceSchematicPalette.CellOrangeOutline
          else -> CadenceSchematicPalette.WireGreen
        }
        FilterChip(
          selected = selected,
          onClick = { onSelectCell(if (selected) null else cell.id) },
          shape = RectangleShape,
          leadingIcon = {
            Box(modifier = Modifier.size(6.dp).background(dotColor))
          },
          label = {
            Text(
              text = if (cell.category.isPort) "${cell.category.badge}:${cell.instanceName}" else cell.instanceName,
              style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp),
            )
          },
          colors = FilterChipDefaults.filterChipColors(
            containerColor = CadenceSchematicPalette.ChromeBg,
            labelColor = CadenceSchematicPalette.ChromeText,
            selectedContainerColor = Color(0xFF2F1906),
            selectedLabelColor = Color.White,
          ),
          modifier = Modifier.height(30.dp).testTag("node_chip_${cell.id}"),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CellInspectorCard(
  cell: SchematicCell,
  graph: SchematicNetlistGraph,
  onSelectCell: (String?) -> Unit,
  onSelectNet: (String?) -> Unit,
  onDrillSubmodule: (String) -> Unit,
  onJumpToRtlLine: (Int) -> Unit,
  onClearSelection: () -> Unit,
) {
  val fanInNets = remember(graph, cell.id) {
    cell.inputPins.mapNotNull { pin -> graph.netByName[pin.netName]?.let { pin to it } }
  }
  val fanOutNets = remember(graph, cell.id) {
    cell.outputPins.mapNotNull { pin -> graph.netByName[pin.netName]?.let { pin to it } }
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(CadenceSchematicPalette.ChromeSurface, RectangleShape)
      .border(
        width = 1.dp,
        color = if (cell.isCriticalPath) CadenceSchematicPalette.WireCriticalRed else CadenceSchematicPalette.CellOrangeOutline,
        shape = RectangleShape,
      )
      .clipToBounds()
      .padding(10.dp)
      .testTag("node_inspector_card"),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Box(
            modifier = Modifier
              .background(Color(0xFF2C1606))
              .border(1.dp, CadenceSchematicPalette.CellOrangeOutline)
              .padding(horizontal = 5.dp, vertical = 2.dp),
          ) {
            Text(
              text = cell.category.badge,
              style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = CadenceSchematicPalette.CellTextAmber),
            )
          }
          Text(
            text = "${cell.instanceName} (${cell.cellType})",
            style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
        }
        val scopeLabel = cell.hierarchyPath.ifBlank { graph.topModuleName }
        Text(
          text = "L${cell.level} · $scopeLabel · out=${cell.primaryOutputNet}[${cell.bitWidth}b]",
          style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = CadenceSchematicPalette.ChromeMuted),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (cell.isCriticalPath) {
          Box(
            modifier = Modifier
              .background(Color(0xFF3A1212))
              .border(1.dp, CadenceSchematicPalette.WireCriticalRed)
              .padding(horizontal = 5.dp, vertical = 2.dp),
          ) {
            Text(
              text = "CRITICAL",
              style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = CadenceSchematicPalette.WireCriticalRed),
            )
          }
        }
        if (cell.sourceLine > 0) {
          IconButton(
            onClick = { onJumpToRtlLine(cell.sourceLine) },
            modifier = Modifier.size(36.dp),
          ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Jump to RTL line ${cell.sourceLine}", tint = CadenceSchematicPalette.WireGreen, modifier = Modifier.size(16.dp))
          }
        }
        IconButton(
          onClick = onClearSelection,
          modifier = Modifier.size(48.dp).testTag("clear_node_selection_button"),
        ) {
          Icon(Icons.Rounded.Close, contentDescription = "Clear selected node", tint = CadenceSchematicPalette.ChromeText)
        }
      }
    }

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      InspectorMetric("Delay", "${String.format(Locale.US, "%.3f", cell.delayNs)}ns")
      InspectorMetric("Arrival", "${String.format(Locale.US, "%.3f", cell.arrivalNs)}ns")
      InspectorMetric("Slack", "${String.format(Locale.US, "%.3f", cell.slackNs)}ns", isError = cell.slackNs < 0.0)
      InspectorMetric("Area", "${String.format(Locale.US, "%.2f", cell.areaUm2)}µm²")
      InspectorMetric("Power", "${String.format(Locale.US, "%.1f", cell.powerUw)}µW")
    }

    if (cell.targetSubmoduleScope != null) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF17271B))
          .border(1.dp, CadenceSchematicPalette.WireGreen)
          .clickable { onDrillSubmodule(cell.targetSubmoduleScope) }
          .padding(horizontal = 8.dp, vertical = 6.dp),
      ) {
        Text(
          text = "Descend Into Submodule: ${cell.targetSubmoduleScope} →",
          style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = CadenceSchematicPalette.WireGreen),
        )
      }
    }

    if (fanInNets.isNotEmpty()) {
      Text(
        text = "INPUT TRACE (${fanInNets.size} pins)",
        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.ChromeMuted),
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        fanInNets.forEach { (pin, net) ->
          val driver = net.driverCellId?.let { graph.cellById[it] }
          TraceTagChip(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            text = ".${pin.pinName}(${net.name})${driver?.let { " ← ${it.instanceName}" } ?: ""}",
            accent = CadenceSchematicPalette.WireFanInCyan,
            onClick = {
              if (driver != null) onSelectCell(driver.id) else onSelectNet(net.name)
            },
          )
        }
      }
    }

    if (fanOutNets.isNotEmpty()) {
      Text(
        text = "OUTPUT TRACE (${fanOutNets.sumOf { it.second.loadPins.size }} loads)",
        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.ChromeMuted),
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        fanOutNets.forEach { (pin, net) ->
          if (net.loadPins.isEmpty()) {
            TraceTagChip(
              icon = Icons.AutoMirrored.Rounded.ArrowForward,
              text = ".${pin.pinName}(${net.name})",
              accent = CadenceSchematicPalette.WireFanOutGold,
              onClick = { onSelectNet(net.name) },
            )
          } else {
            net.loadPins.forEach { (loadId, loadPin) ->
              val loadCell = graph.cellById[loadId]
              TraceTagChip(
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                text = ".${pin.pinName}(${net.name}) → ${loadCell?.instanceName ?: loadId}.$loadPin",
                accent = CadenceSchematicPalette.WireFanOutGold,
                onClick = { onSelectCell(loadId) },
              )
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetInspectorCard(
  net: SchematicNet,
  graph: SchematicNetlistGraph,
  onSelectCell: (String) -> Unit,
  onClearSelection: () -> Unit,
) {
  val driver = net.driverCellId?.let { graph.cellById[it] }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(CadenceSchematicPalette.ChromeSurface, RectangleShape)
      .border(1.dp, CadenceSchematicPalette.WireSelectedNet, RectangleShape)
      .clipToBounds()
      .padding(10.dp)
      .testTag("net_inspector_card"),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
        Box(
          modifier = Modifier
            .background(Color(0xFF102816))
            .border(1.dp, CadenceSchematicPalette.WireGreen)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
          Text("NET", style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = CadenceSchematicPalette.WireGreen))
        }
        Text(
          text = "${net.name}[${net.width}b]",
          style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CadenceSchematicPalette.WireSelectedNet),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (net.isClock) {
          Text("CLK", style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.PortInOutline))
        }
        if (net.isCritical) {
          Text("CRIT", style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.WireCriticalRed))
        }
      }
      IconButton(
        onClick = onClearSelection,
        modifier = Modifier.size(48.dp).testTag("clear_net_selection_button"),
      ) {
        Icon(Icons.Rounded.Close, contentDescription = "Clear selected net", tint = CadenceSchematicPalette.ChromeText)
      }
    }

    if (driver != null) {
      TraceTagChip(
        icon = Icons.AutoMirrored.Rounded.ArrowBack,
        text = "Driver: ${driver.instanceName}.${net.driverPinName ?: "Y"} (${driver.cellType})",
        accent = CadenceSchematicPalette.WireFanInCyan,
        onClick = { onSelectCell(driver.id) },
      )
    }

    if (net.loadPins.isNotEmpty()) {
      Text(
        text = "CONNECTED LOADS (${net.loadPins.size})",
        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.ChromeMuted),
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        net.loadPins.forEach { (cellId, pinName) ->
          val loadCell = graph.cellById[cellId]
          TraceTagChip(
            icon = Icons.AutoMirrored.Rounded.ArrowForward,
            text = "${loadCell?.instanceName ?: cellId}.$pinName",
            accent = CadenceSchematicPalette.WireFanOutGold,
            onClick = { onSelectCell(cellId) },
          )
        }
      }
    }
  }
}

@Composable
private fun TraceTagChip(
  icon: ImageVector,
  text: String,
  accent: Color,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .widthIn(max = 320.dp)
      .background(CadenceSchematicPalette.CanvasBlack, RectangleShape)
      .border(1.dp, accent.copy(alpha = 0.65f), RectangleShape)
      .clickable(onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
    Text(
      text = text,
      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = CadenceSchematicPalette.ChromeText),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
  }
}

@Composable
private fun InspectorMetric(
  label: String,
  value: String,
  isError: Boolean = false,
) {
  Column {
    Text(
      text = label,
      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, color = CadenceSchematicPalette.ChromeMuted),
    )
    Text(
      text = value,
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        color = when {
          isError -> CadenceSchematicPalette.WireCriticalRed
          label == "Slack" -> CadenceSchematicPalette.WireGreen
          else -> Color.White
        },
      ),
    )
  }
}

@Composable
internal fun CadenceBottomStatusBar(
  synthesisResult: SynthesisResult?,
  graph: SchematicNetlistGraph?,
  selectedCell: SchematicCell?,
  selectedNet: SchematicNet?,
  statusDetail: String? = null,
) {
  val report = (synthesisResult as? SynthesisResult.Success)?.report
  val errorResult = synthesisResult as? SynthesisResult.Error

  val (statusLabel, statusColor) = when {
    errorResult != null -> "SYNTH ERROR" to CadenceSchematicPalette.WireCriticalRed
    report != null && report.wnsNs < 0.0 -> "TIMING VIOL" to CadenceSchematicPalette.CellOrangeOutline
    report != null -> "SYNTH OK" to CadenceSchematicPalette.WireGreen
    else -> "NO NETLIST" to CadenceSchematicPalette.ChromeMuted
  }

  val moduleText = graph?.scopeTitle ?: report?.moduleName ?: "—"
  val selectionText = when {
    selectedCell != null -> "CELL ${selectedCell.instanceName} (${selectedCell.cellType}) [${selectedCell.inputPins.size}in/${selectedCell.outputPins.size}out]"
    selectedNet != null -> "NET ${selectedNet.name}[${selectedNet.width}b] (${selectedNet.loadPins.size} loads)"
    else -> statusDetail ?: "Select cell or green wire to trace"
  }
  val statsText = when {
    errorResult != null -> "Line ${errorResult.line} · ${errorResult.log.size} log"
    graph != null && report != null -> "${graph.cells.count { !it.category.isPort }} cells · ${graph.nets.size} nets · ${report.seqCellCount} seq · ${report.warnings.size} warn"
    else -> "0 cells · 0 nets"
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(CadenceSchematicPalette.ChromeBg)
      .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape)
      .clipToBounds()
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 8.dp, vertical = 5.dp)
      .testTag("schematic_status_bar"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Box(modifier = Modifier.size(7.dp).background(statusColor))
      Text(
        text = statusLabel,
        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = statusColor),
      )
    }
    Text("|", style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.ChromeBorder))
    Text(
      text = "MOD: $moduleText",
      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.CellTextAmber),
    )
    Text("|", style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.ChromeBorder))
    Text(
      text = selectionText,
      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.ChromeText),
    )
    Text("|", style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.ChromeBorder))
    Text(
      text = statsText,
      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.5.sp, color = CadenceSchematicPalette.ChromeMuted),
    )
  }
}

@Composable
internal fun StructuralNetlistCard(
  netlist: String,
  onCopy: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val clipboard = LocalClipboardManager.current
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(CadenceSchematicPalette.CanvasBlack, RectangleShape)
      .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(CadenceSchematicPalette.ChromeSurface)
        .padding(horizontal = 12.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Synthesized Gate-Level Verilog",
        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CadenceSchematicPalette.WireGreen),
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
          tint = CadenceSchematicPalette.CellOrangeOutline,
        )
      }
    }
    HorizontalDivider(color = CadenceSchematicPalette.ChromeBorder)
    Box(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .horizontalScroll(rememberScrollState())
        .padding(12.dp),
    ) {
      Text(
        text = netlist,
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 11.5.sp,
          lineHeight = 17.sp,
          color = Color(0xFFBBEAC7),
        ),
      )
    }
  }
}

@Composable
internal fun SynthesisErrorSchematicState(
  error: SynthesisResult.Error,
  onJumpToRtlLine: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(CadenceSchematicPalette.CanvasBlack, RectangleShape)
      .border(1.dp, CadenceSchematicPalette.WireCriticalRed, RectangleShape)
      .clipToBounds()
      .verticalScroll(rememberScrollState())
      .padding(18.dp)
      .testTag("empty_schematic_state"),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = CadenceSchematicPalette.WireCriticalRed, modifier = Modifier.size(22.dp))
      Text(
        text = "Synthesis Error — Schematic Unavailable",
        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CadenceSchematicPalette.WireCriticalRed),
      )
    }
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(CadenceSchematicPalette.ChromeSurface)
        .clickable { if (error.line > 0) onJumpToRtlLine(error.line) }
        .padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = "L${error.line}: ${error.message}",
        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 11.sp, color = CadenceSchematicPalette.CellTextAmber),
        modifier = Modifier.weight(1f),
      )
      if (error.line > 0) {
        Text(
          text = "Jump →",
          style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = CadenceSchematicPalette.WireGreen),
        )
      }
    }
    error.log.take(6).forEach { entry ->
      Text(
        text = entry,
        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = CadenceSchematicPalette.ChromeMuted),
      )
    }
  }
}

@Composable
internal fun EmptySchematicState(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(CadenceSchematicPalette.CanvasBlack, RectangleShape)
      .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape)
      .clipToBounds()
      .verticalScroll(rememberScrollState())
      .padding(24.dp)
      .testTag("empty_schematic_state"),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = Icons.Rounded.AccountTree,
      contentDescription = null,
      tint = CadenceSchematicPalette.CellOrangeOutline,
      modifier = Modifier.size(44.dp),
    )
    Spacer(Modifier.height(10.dp))
    Text(
      text = "No Valid Netlist Yet",
      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White),
    )
    Spacer(Modifier.height(8.dp))
    Text(
      text = "Synthesize Verilog, SystemVerilog, or VHDL RTL in the Editor or Synthesis tab to inspect the gate-level schematic.",
      style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 11.sp, color = CadenceSchematicPalette.ChromeMuted),
      textAlign = TextAlign.Center,
    )
  }
}
