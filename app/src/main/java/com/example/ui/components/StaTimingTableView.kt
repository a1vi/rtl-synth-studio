package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.GateNode
import com.example.domain.synth.SynthesisReport
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale

enum class StaSortColumn(val label: String, val defaultDirection: StaSortDirection) {
  INSTANCE("Gate / Cell", StaSortDirection.ASCENDING),
  DELAY("Delay", StaSortDirection.DESCENDING),
  ARRIVAL("Arrival", StaSortDirection.DESCENDING),
  REQUIRED("Required", StaSortDirection.ASCENDING),
  SLACK("Slack", StaSortDirection.ASCENDING),
}

enum class StaSortDirection {
  ASCENDING,
  DESCENDING,
}

enum class StaRowFilter(val label: String) {
  ALL("All"),
  CRITICAL_PATH("Critical"),
  VIOLATING("Violations"),
}

object StaTableSorter {
  fun sortAndFilter(
    nodes: List<GateNode>,
    sortColumn: StaSortColumn,
    sortDirection: StaSortDirection,
    filter: StaRowFilter,
  ): List<GateNode> {
    val filtered = when (filter) {
      StaRowFilter.ALL -> nodes
      StaRowFilter.CRITICAL_PATH -> nodes.filter { it.isCriticalPath }
      StaRowFilter.VIOLATING -> nodes.filter { it.slackNs < 0.0 }
    }

    val baseComparator = when (sortColumn) {
      StaSortColumn.INSTANCE -> compareBy<GateNode> { it.instanceName.lowercase(Locale.US) }
        .thenBy { it.cellName }
      StaSortColumn.DELAY -> compareBy<GateNode> { it.delayNs }
        .thenBy { it.slackNs }
        .thenBy { it.instanceName }
      StaSortColumn.ARRIVAL -> compareBy<GateNode> { it.arrivalNs }
        .thenBy { it.delayNs }
        .thenBy { it.instanceName }
      StaSortColumn.REQUIRED -> compareBy<GateNode> { it.requiredNs }
        .thenBy { it.slackNs }
        .thenBy { it.instanceName }
      StaSortColumn.SLACK -> compareBy<GateNode> { it.slackNs }
        .thenByDescending { it.delayNs }
        .thenBy { it.instanceName }
    }

    val finalComparator = if (sortDirection == StaSortDirection.DESCENDING) {
      baseComparator.reversed()
    } else {
      baseComparator
    }

    return filtered.sortedWith(finalComparator)
  }
}

@Composable
fun StaTimingTableView(
  report: SynthesisReport,
  onInspectNode: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var sortColumn by remember { mutableStateOf(StaSortColumn.SLACK) }
  var sortDirection by remember { mutableStateOf(StaSortDirection.ASCENDING) }
  var activeFilter by remember { mutableStateOf(StaRowFilter.ALL) }

  val rows = remember(report.nodes, sortColumn, sortDirection, activeFilter) {
    StaTableSorter.sortAndFilter(
      nodes = report.nodes,
      sortColumn = sortColumn,
      sortDirection = sortDirection,
      filter = activeFilter,
    )
  }

  val criticalCount = remember(report.nodes) { report.nodes.count { it.isCriticalPath } }
  val violatingCount = remember(report.nodes) { report.nodes.count { it.slackNs < 0.0 } }
  val hScrollState = rememberScrollState()

  fun onHeaderClick(column: StaSortColumn) {
    if (sortColumn == column) {
      sortDirection = if (sortDirection == StaSortDirection.ASCENDING) {
        StaSortDirection.DESCENDING
      } else {
        StaSortDirection.ASCENDING
      }
    } else {
      sortColumn = column
      sortDirection = column.defaultDirection
    }
  }

  Column(
    modifier = modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Filter chips & active sort indicator
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilterChip(
        selected = activeFilter == StaRowFilter.ALL,
        onClick = { activeFilter = StaRowFilter.ALL },
        label = { Text("All (${report.nodes.size})", style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.testTag("sta_filter_all"),
      )
      FilterChip(
        selected = activeFilter == StaRowFilter.CRITICAL_PATH,
        onClick = { activeFilter = StaRowFilter.CRITICAL_PATH },
        label = { Text("Critical ($criticalCount)", style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
          selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
          selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.testTag("sta_filter_critical"),
      )
      FilterChip(
        selected = activeFilter == StaRowFilter.VIOLATING,
        onClick = { activeFilter = StaRowFilter.VIOLATING },
        label = { Text("Violating ($violatingCount)", style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
          selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
          selectedLabelColor = MaterialTheme.colorScheme.error,
        ),
        modifier = Modifier.testTag("sta_filter_violating"),
      )
    }

    // Structured data table surface with synchronized horizontal scrolling
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 1.dp,
      shape = MaterialTheme.shapes.large,
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.large)
        .testTag("sta_timing_table"),
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Table meta bar
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${rows.size} timing nodes · Clk ${String.format(Locale.US, "%.2f", report.clockPeriodNs)} ns",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          val arrow = if (sortDirection == StaSortDirection.ASCENDING) "↑" else "↓"
          Text(
            text = "Sort: ${sortColumn.label} $arrow",
            style = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.testTag("sta_active_sort_label"),
          )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

        // Scrollable table header + rows
        Column(
          modifier = Modifier
            .weight(1f)
            .horizontalScroll(hScrollState),
        ) {
          // Table Column Headers
          Row(
            modifier = Modifier
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f))
              .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            SortableHeaderCell(
              title = "Gate / Cell",
              column = StaSortColumn.INSTANCE,
              activeColumn = sortColumn,
              direction = sortDirection,
              width = 132.dp,
              alignEnd = false,
              onClick = { onHeaderClick(StaSortColumn.INSTANCE) },
              testTag = "sta_sort_instance",
            )
            SortableHeaderCell(
              title = "Slack",
              column = StaSortColumn.SLACK,
              activeColumn = sortColumn,
              direction = sortDirection,
              width = 80.dp,
              alignEnd = true,
              onClick = { onHeaderClick(StaSortColumn.SLACK) },
              testTag = "sta_sort_slack",
            )
            SortableHeaderCell(
              title = "Delay",
              column = StaSortColumn.DELAY,
              activeColumn = sortColumn,
              direction = sortDirection,
              width = 78.dp,
              alignEnd = true,
              onClick = { onHeaderClick(StaSortColumn.DELAY) },
              testTag = "sta_sort_delay",
            )
            SortableHeaderCell(
              title = "Arrival",
              column = StaSortColumn.ARRIVAL,
              activeColumn = sortColumn,
              direction = sortDirection,
              width = 82.dp,
              alignEnd = true,
              onClick = { onHeaderClick(StaSortColumn.ARRIVAL) },
              testTag = "sta_sort_arrival",
            )
            SortableHeaderCell(
              title = "Required",
              column = StaSortColumn.REQUIRED,
              activeColumn = sortColumn,
              direction = sortDirection,
              width = 86.dp,
              alignEnd = true,
              onClick = { onHeaderClick(StaSortColumn.REQUIRED) },
              testTag = "sta_sort_required",
            )
            StaticHeaderCell(
              title = "Net / Stage",
              width = 98.dp,
            )
          }

          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

          if (rows.isEmpty()) {
            Box(
              modifier = Modifier
                .width(640.dp)
                .padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = if (activeFilter == StaRowFilter.VIOLATING) {
                  "No timing violations — all paths meet the ${String.format(Locale.US, "%.2f", report.clockPeriodNs)} ns clock constraint."
                } else {
                  "No matching timing nodes for this filter."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            LazyColumn(
              modifier = Modifier
                .weight(1f)
                .testTag("sta_table_list"),
            ) {
              itemsIndexed(rows, key = { _, node -> node.id }) { index, node ->
                StaTimingRow(
                  node = node,
                  striped = index % 2 == 1,
                  onClick = { onInspectNode(node.id) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SortableHeaderCell(
  title: String,
  column: StaSortColumn,
  activeColumn: StaSortColumn,
  direction: StaSortDirection,
  width: Dp,
  alignEnd: Boolean,
  onClick: () -> Unit,
  testTag: String,
) {
  val isActive = column == activeColumn
  val fg = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
  val icon = when {
    !isActive -> Icons.Rounded.UnfoldMore
    direction == StaSortDirection.ASCENDING -> Icons.Rounded.ArrowUpward
    else -> Icons.Rounded.ArrowDownward
  }

  Row(
    modifier = Modifier
      .width(width)
      .heightIn(min = 48.dp)
      .clickable(onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 8.dp)
      .testTag(testTag),
    horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelSmall.copy(
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
      ),
      color = fg,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Icon(
      imageVector = icon,
      contentDescription = "Sort by $title",
      tint = fg,
      modifier = Modifier
        .padding(start = 3.dp)
        .size(14.dp),
    )
  }
}

@Composable
private fun StaticHeaderCell(
  title: String,
  width: Dp,
) {
  Box(
    modifier = Modifier
      .width(width)
      .heightIn(min = 48.dp)
      .padding(horizontal = 6.dp, vertical = 8.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun StaTimingRow(
  node: GateNode,
  striped: Boolean,
  onClick: () -> Unit,
) {
  val rowBg = when {
    node.slackNs < 0.0 -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f)
    node.isCriticalPath -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.20f)
    striped -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    else -> Color.Transparent
  }
  val slackColor = when {
    node.slackNs < 0.0 -> MaterialTheme.colorScheme.error
    node.isCriticalPath -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.tertiary
  }
  val monoStyle = TextStyle(
    fontFamily = JetBrainsMonoFamily,
    fontSize = 11.5.sp,
    color = MaterialTheme.colorScheme.onSurface,
  )

  Row(
    modifier = Modifier
      .background(rowBg)
      .heightIn(min = 48.dp)
      .clickable(onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 6.dp)
      .testTag("sta_row_${node.id}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Gate / Cell column
    Column(
      modifier = Modifier
        .width(132.dp)
        .padding(horizontal = 6.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = node.instanceName,
          style = monoStyle.copy(fontWeight = FontWeight.SemiBold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (node.isFalsePath) {
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.extraSmall,
          ) {
            Text(
              text = "FP",
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
              ),
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
          }
        } else if (node.isCriticalPath) {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.extraSmall,
          ) {
            Text(
              text = "CRIT",
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
              ),
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
          }
        }
      }
      Text(
        text = node.cellName,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    // Slack (ns)
    Text(
      text = if (node.isFalsePath) "EXEMPT" else "${if (node.slackNs >= 0.0) "+" else ""}${String.format(Locale.US, "%.3f", node.slackNs)} ns",
      style = monoStyle.copy(
        fontWeight = FontWeight.Bold,
        color = if (node.isFalsePath) MaterialTheme.colorScheme.tertiary else slackColor,
      ),
      textAlign = TextAlign.End,
      modifier = Modifier
        .width(80.dp)
        .padding(horizontal = 4.dp),
    )

    // Delay (ns)
    Text(
      text = "${String.format(Locale.US, "%.3f", node.delayNs)} ns",
      style = monoStyle.copy(
        fontWeight = if (node.delayNs >= 0.1) FontWeight.Medium else FontWeight.Normal,
      ),
      textAlign = TextAlign.End,
      modifier = Modifier
        .width(78.dp)
        .padding(horizontal = 4.dp),
    )

    // Arrival (ns)
    Text(
      text = "${String.format(Locale.US, "%.3f", node.arrivalNs)} ns",
      style = monoStyle,
      textAlign = TextAlign.End,
      modifier = Modifier
        .width(82.dp)
        .padding(horizontal = 4.dp),
    )

    // Required (ns)
    Text(
      text = "${String.format(Locale.US, "%.3f", node.requiredNs)} ns",
      style = monoStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
      textAlign = TextAlign.End,
      modifier = Modifier
        .width(86.dp)
        .padding(horizontal = 4.dp),
    )

    // Net / Stage column
    Column(
      modifier = Modifier
        .width(98.dp)
        .padding(horizontal = 6.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = if (node.bitWidth > 1) "${node.outputNet}[${node.bitWidth}]" else node.outputNet,
        style = monoStyle,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "L${node.level} · ${node.clockDomain}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
