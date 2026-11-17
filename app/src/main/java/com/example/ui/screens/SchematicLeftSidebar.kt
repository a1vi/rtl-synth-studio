package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.SchematicCell
import com.example.domain.synth.SchematicHierarchyNode
import com.example.domain.synth.SchematicNetlistGraph
import com.example.ui.components.CadenceSchematicPalette
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun SchematicLeftSidebar(
  graph: SchematicNetlistGraph,
  activeScopePath: String,
  selectedCellId: String?,
  selectedSourceLine: Int,
  rtlSource: String,
  onSelectScope: (String) -> Unit,
  onSelectCell: (String) -> Unit,
  onJumpToRtlLine: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  var sidebarTab by remember { mutableIntStateOf(0) } // 0 = Hierarchy, 1 = RTL Source
  val expandedNodes = remember(graph.topModuleName) {
    mutableStateMapOf("" to true, activeScopePath to true)
  }
  val expandedGroups = remember(activeScopePath) {
    mutableStateMapOf("SEQ" to true, "LOGIC" to true, "PORTS" to false)
  }

  Column(
    modifier = modifier
      .background(CadenceSchematicPalette.ChromeBg)
      .border(1.dp, CadenceSchematicPalette.ChromeBorder, RectangleShape)
      .clipToBounds()
      .testTag("schematic_left_sidebar"),
  ) {
    // Sidebar Mode Header: Hierarchy Tree | RTL Source
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(CadenceSchematicPalette.ChromeSurface),
    ) {
      SidebarTabButton(
        label = "Hierarchy",
        icon = Icons.Rounded.AccountTree,
        selected = sidebarTab == 0,
        onClick = { sidebarTab = 0 },
        modifier = Modifier.weight(1f).testTag("sidebar_tab_hierarchy"),
      )
      SidebarTabButton(
        label = "RTL Source",
        icon = Icons.Rounded.Code,
        selected = sidebarTab == 1,
        onClick = { sidebarTab = 1 },
        modifier = Modifier.weight(1f).testTag("sidebar_tab_rtl"),
      )
    }
    HorizontalDivider(color = CadenceSchematicPalette.ChromeBorder)

    if (sidebarTab == 0) {
      val portCells = remember(graph.cells) { graph.cells.filter { it.category.isPort } }
      val seqCells = remember(graph.cells) { graph.cells.filter { !it.category.isPort && it.category.isSequential } }
      val combCells = remember(graph.cells) { graph.cells.filter { !it.category.isPort && !it.category.isSequential } }

      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(vertical = 4.dp)
          .testTag("schematic_hierarchy_tree"),
      ) {
        item {
          HierarchyTreeBranch(
            node = graph.hierarchyRoot,
            depth = 0,
            activeScopePath = activeScopePath,
            expandedMap = expandedNodes,
            onToggleExpand = { scope -> expandedNodes[scope] = !(expandedNodes[scope] ?: false) },
            onSelectScope = onSelectScope,
          )
        }

        item {
          HorizontalDivider(
            color = CadenceSchematicPalette.ChromeBorder.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = 4.dp),
          )
          Text(
            text = "INSTANCE BROWSER (${graph.cells.size})",
            style = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 9.sp,
              fontWeight = FontWeight.Bold,
              color = CadenceSchematicPalette.ChromeMuted,
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }

        // Sequential Registers group
        if (seqCells.isNotEmpty()) {
          item {
            InstanceGroupHeader(
              title = "Registers / DFF (${seqCells.size})",
              accent = CadenceSchematicPalette.CellOrangeOutline,
              expanded = expandedGroups["SEQ"] == true,
              onClick = { expandedGroups["SEQ"] = !(expandedGroups["SEQ"] ?: false) },
            )
          }
          if (expandedGroups["SEQ"] == true) {
            items(seqCells.size, key = { "seq_${seqCells[it].id}" }) { idx ->
              val cell = seqCells[idx]
              HierarchyCellRow(
                cell = cell,
                selected = cell.id == selectedCellId,
                onClick = { onSelectCell(cell.id) },
              )
            }
          }
        }

        // Combinational Standard Cells group
        if (combCells.isNotEmpty()) {
          item {
            InstanceGroupHeader(
              title = "Std Cells (${combCells.size})",
              accent = CadenceSchematicPalette.WireGreen,
              expanded = expandedGroups["LOGIC"] == true,
              onClick = { expandedGroups["LOGIC"] = !(expandedGroups["LOGIC"] ?: false) },
            )
          }
          if (expandedGroups["LOGIC"] == true) {
            items(combCells.size, key = { "comb_${combCells[it].id}" }) { idx ->
              val cell = combCells[idx]
              HierarchyCellRow(
                cell = cell,
                selected = cell.id == selectedCellId,
                onClick = { onSelectCell(cell.id) },
              )
            }
          }
        }

        // Ports group
        if (portCells.isNotEmpty()) {
          item {
            InstanceGroupHeader(
              title = "I/O Ports (${portCells.size})",
              accent = CadenceSchematicPalette.PortInOutline,
              expanded = expandedGroups["PORTS"] == true,
              onClick = { expandedGroups["PORTS"] = !(expandedGroups["PORTS"] ?: false) },
            )
          }
          if (expandedGroups["PORTS"] == true) {
            items(portCells.size, key = { "port_${portCells[it].id}" }) { idx ->
              val cell = portCells[idx]
              HierarchyCellRow(
                cell = cell,
                selected = cell.id == selectedCellId,
                onClick = { onSelectCell(cell.id) },
              )
            }
          }
        }
      }
    } else {
      val lines = remember(rtlSource) {
        rtlSource.ifBlank { "// No RTL source loaded" }.lines()
      }
      val listState = rememberLazyListState()

      LaunchedEffect(selectedSourceLine) {
        if (selectedSourceLine > 0 && selectedSourceLine <= lines.size) {
          listState.animateScrollToItem((selectedSourceLine - 2).coerceAtLeast(0))
        }
      }

      Column(modifier = Modifier.fillMaxSize().testTag("schematic_rtl_viewer")) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(CadenceSchematicPalette.ChromeSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = if (selectedSourceLine > 0) "Line $selectedSourceLine active" else "${lines.size} lines",
            style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = CadenceSchematicPalette.WireGreen),
          )
          if (selectedSourceLine > 0) {
            IconButton(
              onClick = { onJumpToRtlLine(selectedSourceLine) },
              modifier = Modifier.size(32.dp),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = "Open line in RTL editor",
                tint = CadenceSchematicPalette.CellOrangeOutline,
                modifier = Modifier.size(14.dp),
              )
            }
          }
        }
        HorizontalDivider(color = CadenceSchematicPalette.ChromeBorder)
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxSize()
            .background(CadenceSchematicPalette.CanvasBlack)
            .horizontalScroll(rememberScrollState()),
        ) {
          itemsIndexed(lines) { index, lineText ->
            val lineNum = index + 1
            val isHighlighted = lineNum == selectedSourceLine
            Row(
              modifier = Modifier
                .widthIn(min = 236.dp)
                .background(if (isHighlighted) Color(0xFF341A05) else Color.Transparent)
                .clickable { onJumpToRtlLine(lineNum) }
                .padding(horizontal = 6.dp, vertical = 1.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier = Modifier
                  .width(3.dp)
                  .height(14.dp)
                  .background(if (isHighlighted) CadenceSchematicPalette.CellOrangeOutline else Color.Transparent),
              )
              Spacer(Modifier.width(4.dp))
              Text(
                text = lineNum.toString().padStart(3, ' '),
                style = TextStyle(
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = 9.5.sp,
                  color = if (isHighlighted) CadenceSchematicPalette.CellTextAmber else CadenceSchematicPalette.ChromeMuted,
                ),
                softWrap = false,
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = lineText.ifEmpty { " " },
                style = TextStyle(
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = 10.sp,
                  color = if (isHighlighted) Color.White else Color(0xFFAADDBB),
                ),
                softWrap = false,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SidebarTabButton(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .heightIn(min = 42.dp)
      .background(if (selected) CadenceSchematicPalette.CanvasBlack else CadenceSchematicPalette.ChromeSurface)
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      tint = if (selected) CadenceSchematicPalette.CellOrangeOutline else CadenceSchematicPalette.ChromeMuted,
      modifier = Modifier.size(14.dp),
    )
    Spacer(Modifier.width(4.dp))
    Text(
      text = label,
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = 10.5.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = if (selected) CadenceSchematicPalette.ChromeText else CadenceSchematicPalette.ChromeMuted,
      ),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun HierarchyTreeBranch(
  node: SchematicHierarchyNode,
  depth: Int,
  activeScopePath: String,
  expandedMap: MutableMap<String, Boolean>,
  onToggleExpand: (String) -> Unit,
  onSelectScope: (String) -> Unit,
) {
  val isSelected = node.scopePath == activeScopePath
  val isExpanded = expandedMap[node.scopePath] ?: (depth == 0)
  val hasChildren = node.children.isNotEmpty()

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 36.dp)
      .background(if (isSelected) Color(0xFF1F2D22) else Color.Transparent)
      .clickable { onSelectScope(node.scopePath) }
      .padding(start = (6 + depth * 12).dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (hasChildren) {
      Icon(
        imageVector = if (isExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
        contentDescription = "Toggle module children",
        tint = CadenceSchematicPalette.ChromeMuted,
        modifier = Modifier
          .size(16.dp)
          .clickable { onToggleExpand(node.scopePath) },
      )
    } else {
      Spacer(Modifier.width(16.dp))
    }
    Icon(
      imageVector = Icons.Rounded.Memory,
      contentDescription = null,
      tint = if (isSelected) CadenceSchematicPalette.WireGreen else CadenceSchematicPalette.CellOrangeOutline,
      modifier = Modifier.size(14.dp),
    )
    Spacer(Modifier.width(5.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = if (node.scopePath.isEmpty()) node.moduleType else "${node.instanceName} (${node.moduleType})",
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 10.5.sp,
          fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
          color = if (isSelected) CadenceSchematicPalette.WireGreen else CadenceSchematicPalette.ChromeText,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "${node.cellCount}c · ${node.seqCount}ff · ${node.netCount}n",
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 8.5.sp,
          color = CadenceSchematicPalette.ChromeMuted,
        ),
      )
    }
  }

  if (hasChildren && isExpanded) {
    node.children.forEach { child ->
      HierarchyTreeBranch(
        node = child,
        depth = depth + 1,
        activeScopePath = activeScopePath,
        expandedMap = expandedMap,
        onToggleExpand = onToggleExpand,
        onSelectScope = onSelectScope,
      )
    }
  }
}

@Composable
private fun InstanceGroupHeader(
  title: String,
  accent: Color,
  expanded: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 34.dp)
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
      contentDescription = null,
      tint = accent,
      modifier = Modifier.size(14.dp),
    )
    Spacer(Modifier.width(4.dp))
    Text(
      text = title,
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = accent,
      ),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun HierarchyCellRow(
  cell: SchematicCell,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val dotColor = when {
    cell.isCriticalPath -> CadenceSchematicPalette.WireCriticalRed
    cell.category.isPort -> CadenceSchematicPalette.PortInOutline
    cell.category.isSequential -> CadenceSchematicPalette.CellOrangeOutline
    else -> CadenceSchematicPalette.WireGreen
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 32.dp)
      .background(if (selected) Color(0xFF331B06) else Color.Transparent)
      .clickable(onClick = onClick)
      .padding(start = 18.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.size(6.dp).background(dotColor))
    Spacer(Modifier.width(6.dp))
    Text(
      text = "${cell.instanceName} : ${cell.cellType}",
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = 9.5.sp,
        color = if (selected) Color.White else CadenceSchematicPalette.ChromeText,
      ),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
