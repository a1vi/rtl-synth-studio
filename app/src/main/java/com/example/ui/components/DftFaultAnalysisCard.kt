package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.AtpgFaultSite
import com.example.domain.synth.DftReport
import com.example.domain.synth.FaultModelCategory
import com.example.domain.synth.FaultStatus
import com.example.domain.synth.FaultType
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

enum class DftFaultModelFilter(
  val label: String,
  val isTransitionModel: Boolean,
  val matchingTypes: Set<FaultType>,
) {
  ALL_MODELS("All Models", false, setOf(FaultType.SA0, FaultType.SA1, FaultType.STR, FaultType.STF)),
  COMBINED_SA("Stuck-At (SA0+SA1)", false, setOf(FaultType.SA0, FaultType.SA1)),
  SA0_ONLY("SA0", false, setOf(FaultType.SA0)),
  SA1_ONLY("SA1", false, setOf(FaultType.SA1)),
  TRANSITION_DELAY("Transition Delay", true, setOf(FaultType.STR, FaultType.STF)),
  STR_ONLY("STR (Rise)", true, setOf(FaultType.STR)),
  STF_ONLY("STF (Fall)", true, setOf(FaultType.STF)),
}

enum class DftFaultStatusFilter(val label: String) {
  ALL("All"),
  DETECTED("Detected"),
  UNDETECTED("Undetected"),
  UNTESTABLE("Untestable"),
}

data class FaultModelSummaryRow(
  val faultType: FaultType?,
  val key: String,
  val label: String,
  val categoryLabel: String,
  val total: Int,
  val detected: Int,
  val undetected: Int,
  val untestable: Int,
) {
  val coveragePct: Double
    get() = if (total > 0) (detected * 100.0) / total else 100.0
}

enum class FaultKpiStatusTier {
  PASS,
  INFO,
  WARNING,
  CRITICAL,
  NEUTRAL,
}

data class FaultKpiBadge(
  val label: String,
  val tier: FaultKpiStatusTier,
)

data class FaultKpiSummary(
  val totalFaults: Int,
  val faultCoveragePct: Double,
  val untestableFaults: Int,
  val detectedFaults: Int,
  val totalStatus: FaultKpiBadge,
  val coverageStatus: FaultKpiBadge,
  val untestableStatus: FaultKpiBadge,
)

object DftFaultAnalysisHelper {
  fun collectAllFaults(dft: DftReport): List<AtpgFaultSite> =
    dft.sampleFaults + dft.transitionFaults

  fun faultsForModel(
    dft: DftReport,
    modelFilter: DftFaultModelFilter,
  ): List<AtpgFaultSite> =
    collectAllFaults(dft).filter { it.faultType in modelFilter.matchingTypes }

  fun buildSummaryRows(
    dft: DftReport,
    modelFilter: DftFaultModelFilter,
  ): List<FaultModelSummaryRow> {
    val activeFaults = faultsForModel(dft, modelFilter)
    val orderedTypes = FaultType.entries.filter { it in modelFilter.matchingTypes }
    return orderedTypes.map { type ->
      val matching = activeFaults.filter { it.faultType == type }
      val det = matching.count { it.status == FaultStatus.DETECTED }
      val undet = matching.count { it.status == FaultStatus.UNDETECTED }
      val untest = matching.count { it.status == FaultStatus.UNTESTABLE }
      FaultModelSummaryRow(
        faultType = type,
        key = type.name,
        label = "${type.shortLabel} (${type.fullName})",
        categoryLabel = type.category.displayName,
        total = matching.size,
        detected = det,
        undetected = undet,
        untestable = untest,
      )
    }
  }

  fun buildTotalRow(rows: List<FaultModelSummaryRow>): FaultModelSummaryRow {
    val total = rows.sumOf { it.total }
    val det = rows.sumOf { it.detected }
    val undet = rows.sumOf { it.undetected }
    val untest = rows.sumOf { it.untestable }
    return FaultModelSummaryRow(
      faultType = null,
      key = "TOTAL",
      label = "Total (Filtered)",
      categoryLabel = "Combined",
      total = total,
      detected = det,
      undetected = undet,
      untestable = untest,
    )
  }

  fun filterFaultSites(
    faults: List<AtpgFaultSite>,
    statusFilter: DftFaultStatusFilter,
    query: String,
  ): List<AtpgFaultSite> {
    val q = query.trim().lowercase(Locale.US)
    return faults.filter { fault ->
      val statusOk = when (statusFilter) {
        DftFaultStatusFilter.ALL -> true
        DftFaultStatusFilter.DETECTED -> fault.status == FaultStatus.DETECTED
        DftFaultStatusFilter.UNDETECTED -> fault.status == FaultStatus.UNDETECTED
        DftFaultStatusFilter.UNTESTABLE -> fault.status == FaultStatus.UNTESTABLE
      }
      val queryOk = q.isEmpty() ||
        fault.netName.lowercase(Locale.US).contains(q) ||
        fault.nodeInstance.lowercase(Locale.US).contains(q) ||
        fault.faultType.shortLabel.lowercase(Locale.US).contains(q)
      statusOk && queryOk
    }
  }

  fun buildKpiSummary(
    dft: DftReport,
    summary: FaultModelSummaryRow,
  ): FaultKpiSummary {
    val totalStatus = when {
      summary.total == 0 -> FaultKpiBadge("NO SITES", FaultKpiStatusTier.NEUTRAL)
      summary.undetected == 0 && summary.untestable == 0 ->
        FaultKpiBadge("100% HIT", FaultKpiStatusTier.PASS)
      else -> FaultKpiBadge("${summary.detected} DET", FaultKpiStatusTier.INFO)
    }

    val coverageStatus = when {
      summary.total == 0 -> FaultKpiBadge("N/A", FaultKpiStatusTier.NEUTRAL)
      summary.coveragePct >= 90.0 -> FaultKpiBadge("PASS", FaultKpiStatusTier.PASS)
      summary.coveragePct >= 70.0 -> FaultKpiBadge("MARGINAL", FaultKpiStatusTier.WARNING)
      else -> FaultKpiBadge("LOW COV", FaultKpiStatusTier.CRITICAL)
    }

    val untestableStatus = when {
      summary.total == 0 -> FaultKpiBadge("N/A", FaultKpiStatusTier.NEUTRAL)
      summary.untestable == 0 -> FaultKpiBadge("CLEAN", FaultKpiStatusTier.PASS)
      !dft.scanInserted && summary.untestable * 4 >= summary.total ->
        FaultKpiBadge("NO SCAN", FaultKpiStatusTier.CRITICAL)
      !dft.scanInserted -> FaultKpiBadge("NO SCAN", FaultKpiStatusTier.WARNING)
      else -> FaultKpiBadge("BLOCKED", FaultKpiStatusTier.WARNING)
    }

    return FaultKpiSummary(
      totalFaults = summary.total,
      faultCoveragePct = summary.coveragePct,
      untestableFaults = summary.untestable,
      detectedFaults = summary.detected,
      totalStatus = totalStatus,
      coverageStatus = coverageStatus,
      untestableStatus = untestableStatus,
    )
  }
}

@Composable
fun DftFaultAnalysisCard(
  dft: DftReport,
  onLocateNodeInSchematic: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current

  var faultModel by rememberSaveable { mutableStateOf(DftFaultModelFilter.ALL_MODELS) }
  var statusFilter by rememberSaveable { mutableStateOf(DftFaultStatusFilter.ALL) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var showAllSites by rememberSaveable { mutableStateOf(false) }

  val modelFaults = remember(dft, faultModel) {
    DftFaultAnalysisHelper.faultsForModel(dft, faultModel)
  }
  val summaryRows = remember(dft, faultModel) {
    DftFaultAnalysisHelper.buildSummaryRows(dft, faultModel)
  }
  val totalSummary = remember(summaryRows) {
    DftFaultAnalysisHelper.buildTotalRow(summaryRows)
  }
  val filteredSites = remember(modelFaults, statusFilter, searchQuery) {
    DftFaultAnalysisHelper.filterFaultSites(modelFaults, statusFilter, searchQuery)
  }

  val statusCounts = remember(modelFaults) {
    mapOf(
      DftFaultStatusFilter.ALL to modelFaults.size,
      DftFaultStatusFilter.DETECTED to modelFaults.count { it.status == FaultStatus.DETECTED },
      DftFaultStatusFilter.UNDETECTED to modelFaults.count { it.status == FaultStatus.UNDETECTED },
      DftFaultStatusFilter.UNTESTABLE to modelFaults.count { it.status == FaultStatus.UNTESTABLE },
    )
  }

  val activeCovColor = when {
    totalSummary.coveragePct >= 90.0 -> eda.success
    totalSummary.coveragePct >= 65.0 -> eda.cyanHighlight
    else -> eda.warning
  }
  val activeCovContainer = when {
    totalSummary.coveragePct >= 90.0 -> eda.successContainer
    totalSummary.coveragePct >= 65.0 -> eda.cyanContainer
    else -> eda.warningContainer
  }

  EdaSurfaceCard(modifier = modifier.fillMaxWidth().testTag("dft_fault_analysis_card")) {
    EdaPanelHeader(
      title = "Fault Analysis",
      subtitle = "${totalSummary.total} faults · ${totalSummary.detected} det · ${totalSummary.undetected} undet",
      icon = Icons.Rounded.BugReport,
      trailing = {
        EdaStatusPill(
          text = "${"%.1f".format(Locale.US, totalSummary.coveragePct)}% COV",
          color = activeCovColor,
          containerColor = activeCovContainer,
        )
      },
    )

    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Fault Model Filter Bar (Stuck-At & Transition Delay)
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = "FAULT MODEL FILTER",
          style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.SemiBold,
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          DftFaultModelFilter.entries.forEach { model ->
            val tagSuffix = when (model) {
              DftFaultModelFilter.ALL_MODELS -> "all"
              else -> model.name.lowercase(Locale.US)
            }
            FilterChip(
              selected = faultModel == model,
              onClick = { faultModel = model },
              label = { Text(model.label, style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.testTag("dft_fault_model_$tagSuffix"),
            )
          }
        }
      }

      if (faultModel.isTransitionModel) {
        Surface(
          color = eda.warningContainer.copy(alpha = 0.65f),
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, eda.warning.copy(alpha = 0.45f)),
          modifier = Modifier.fillMaxWidth().testTag("dft_unsupported_fault_model_notice"),
        ) {
          Text(
            text = "${faultModel.label} is evaluated using structural two-cycle Launch-on-Capture (V1->V2) vector pairs. Dynamic SDF timing-annotated at-speed ATPG is not run on-device.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
          )
        }
      }

      val kpiSummary = remember(dft, totalSummary) {
        DftFaultAnalysisHelper.buildKpiSummary(dft, totalSummary)
      }

      // KPI Summary Card Section (Total Faults | Fault Coverage | Untestable Faults)
      FaultKpiSummarySection(kpi = kpiSummary)

      // Summary Table: Fault Model | Total | Detected | Undetected | Cov %
      Surface(
        color = eda.codeSurface.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.75f)),
        modifier = Modifier.fillMaxWidth().testTag("dft_fault_summary_table"),
      ) {
        Column(modifier = Modifier.fillMaxWidth()) {
          // Table Header
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
              .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "FAULT MODEL",
              style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
              ),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1.75f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "TOTAL",
              style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
              ),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.End,
              modifier = Modifier.weight(0.75f),
            )
            Text(
              text = "DET",
              style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
              ),
              color = eda.success,
              textAlign = TextAlign.End,
              modifier = Modifier.weight(0.75f),
            )
            Text(
              text = "UNDET",
              style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
              ),
              color = eda.warning,
              textAlign = TextAlign.End,
              modifier = Modifier.weight(0.85f),
            )
            Text(
              text = "COV%",
              style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
              ),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.End,
              modifier = Modifier.weight(0.9f),
            )
          }
          HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.75f))

          summaryRows.forEach { row ->
            FaultSummaryTableRowItem(
              row = row,
              isTotalRow = false,
              onClick = {
                faultModel = when (row.faultType) {
                  FaultType.SA0 -> DftFaultModelFilter.SA0_ONLY
                  FaultType.SA1 -> DftFaultModelFilter.SA1_ONLY
                  FaultType.STR -> DftFaultModelFilter.STR_ONLY
                  FaultType.STF -> DftFaultModelFilter.STF_ONLY
                  null -> faultModel
                }
              },
            )
            HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.35f))
          }

          FaultSummaryTableRowItem(
            row = totalSummary,
            isTotalRow = true,
            onClick = null,
          )
        }
      }

      if (totalSummary.untestable > 0) {
        Text(
          text = "Note: ${totalSummary.untestable} non-scan sequential fault(s) marked Untestable (Total = Detected + Undetected + Untestable).",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("dft_fault_untestable_footnote"),
        )
      }

      HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.45f))

      // Status Filter + Search
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        DftFaultStatusFilter.entries.forEach { sf ->
          val count = statusCounts[sf] ?: 0
          FilterChip(
            selected = statusFilter == sf,
            onClick = { statusFilter = sf },
            label = {
              Text(
                text = "${sf.label} ($count)",
                style = MaterialTheme.typography.labelSmall,
              )
            },
            modifier = Modifier.testTag("dft_fault_filter_${sf.name}"),
          )
        }
      }

      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        singleLine = true,
        placeholder = {
          Text(
            text = "Filter net or gate instance (e.g. count, u_dff)...",
            style = MaterialTheme.typography.bodySmall,
          )
        },
        leadingIcon = {
          Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = "Search fault sites",
            modifier = Modifier.size(18.dp),
          )
        },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(
              onClick = { searchQuery = "" },
              modifier = Modifier.testTag("dft_fault_search_clear"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Clear,
                contentDescription = "Clear fault search",
                modifier = Modifier.size(18.dp),
              )
            }
          }
        },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
        modifier = Modifier.fillMaxWidth().testTag("dft_fault_search_input"),
      )

      // Detailed Fault Sites Table
      if (filteredSites.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag("dft_fault_sites_empty"),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "No fault sites match the selected model and status filters.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        val visibleLimit = if (showAllSites) 40 else 8
        val visibleSites = filteredSites.take(visibleLimit)

        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.6f)),
          modifier = Modifier.fillMaxWidth().testTag("dft_fault_sites_table"),
        ) {
          Column(modifier = Modifier.fillMaxWidth()) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "FAULT SITE (NET / CELL)",
                style = MaterialTheme.typography.labelSmall.copy(
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1.9f),
              )
              Text(
                text = "MODEL",
                style = MaterialTheme.typography.labelSmall.copy(
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(0.65f),
              )
              Text(
                text = "STATUS",
                style = MaterialTheme.typography.labelSmall.copy(
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.95f),
              )
            }
            HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.6f))

            visibleSites.forEachIndexed { idx, fault ->
              val fColor = when (fault.status) {
                FaultStatus.DETECTED -> eda.success
                FaultStatus.UNDETECTED -> eda.warning
                FaultStatus.UNTESTABLE -> MaterialTheme.colorScheme.onSurfaceVariant
              }
              val modelAccent = when (fault.faultType.category) {
                FaultModelCategory.STUCK_AT -> MaterialTheme.colorScheme.primary
                FaultModelCategory.TRANSITION_DELAY -> eda.cyanHighlight
              }
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 48.dp)
                  .clickable { onLocateNodeInSchematic(fault.nodeId) }
                  .padding(horizontal = 10.dp, vertical = 6.dp)
                  .testTag("dft_fault_site_row_${fault.id}"),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  modifier = Modifier.weight(1.9f).padding(end = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.AccountTree,
                    contentDescription = "Locate ${fault.nodeInstance} in schematic",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                  )
                  Column {
                    Text(
                      text = fault.netName,
                      style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Medium,
                      ),
                      color = MaterialTheme.colorScheme.onSurface,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                      text = fault.nodeInstance,
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
                Text(
                  text = fault.faultType.shortLabel,
                  style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                  ),
                  color = modelAccent,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.weight(0.65f),
                )
                Text(
                  text = when {
                    fault.detectedByVectorIndex != null -> "DET #${fault.detectedByVectorIndex}"
                    fault.status == FaultStatus.UNDETECTED -> "UNDET"
                    else -> "UNTEST"
                  },
                  style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                  ),
                  color = fColor,
                  textAlign = TextAlign.End,
                  modifier = Modifier.weight(0.95f),
                )
              }
              if (idx < visibleSites.lastIndex) {
                HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.25f))
              }
            }
          }
        }

        if (filteredSites.size > 8) {
          TextButton(
            onClick = { showAllSites = !showAllSites },
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("dft_fault_sites_expand_button"),
          ) {
            Text(
              text = if (showAllSites) {
                "Show Top 8 Fault Sites"
              } else {
                val capNote = if (filteredSites.size > 40) "top 40 of ${filteredSites.size}" else "${filteredSites.size} total"
                "Show More Fault Sites ($capNote)"
              },
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun FaultSummaryTableRowItem(
  row: FaultModelSummaryRow,
  isTotalRow: Boolean,
  onClick: (() -> Unit)?,
) {
  val eda = LocalEdaColors.current
  val weight = if (isTotalRow) FontWeight.Bold else FontWeight.Medium
  val rowBg = if (isTotalRow) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
  } else {
    MaterialTheme.colorScheme.surface.copy(alpha = 0f)
  }
  val covColor = when {
    row.coveragePct >= 90.0 -> eda.success
    row.coveragePct >= 65.0 -> eda.cyanHighlight
    else -> eda.warning
  }
  val undetColor = if (row.undetected > 0) eda.warning else MaterialTheme.colorScheme.onSurfaceVariant

  val baseMod = Modifier
    .fillMaxWidth()
    .background(rowBg)
    .let { mod ->
      if (onClick != null) {
        mod.heightIn(min = 48.dp).clickable(onClick = onClick)
      } else {
        mod.heightIn(min = 42.dp)
      }
    }
    .padding(horizontal = 10.dp, vertical = 6.dp)
    .testTag("dft_fault_row_${row.key}")

  Row(
    modifier = baseMod,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1.75f).padding(end = 6.dp)) {
      Text(
        text = row.label,
        style = MaterialTheme.typography.labelSmall.copy(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = weight,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!isTotalRow) {
        Text(
          text = row.categoryLabel,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Text(
      text = row.total.toString(),
      style = MaterialTheme.typography.labelMedium.copy(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = weight,
      ),
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.End,
      modifier = Modifier
        .weight(0.75f)
        .let { if (isTotalRow) it.testTag("dft_fault_summary_total_value") else it },
    )
    Text(
      text = row.detected.toString(),
      style = MaterialTheme.typography.labelMedium.copy(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = weight,
      ),
      color = eda.success,
      textAlign = TextAlign.End,
      modifier = Modifier
        .weight(0.75f)
        .let { if (isTotalRow) it.testTag("dft_fault_summary_detected_value") else it },
    )
    Text(
      text = row.undetected.toString(),
      style = MaterialTheme.typography.labelMedium.copy(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = weight,
      ),
      color = undetColor,
      textAlign = TextAlign.End,
      modifier = Modifier
        .weight(0.85f)
        .let { if (isTotalRow) it.testTag("dft_fault_summary_undetected_value") else it },
    )
    Text(
      text = "${"%.1f".format(Locale.US, row.coveragePct)}%",
      style = MaterialTheme.typography.labelMedium.copy(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = weight,
      ),
      color = covColor,
      textAlign = TextAlign.End,
      modifier = Modifier.weight(0.9f),
    )
  }
}

@Composable
private fun FaultKpiSummarySection(
  kpi: FaultKpiSummary,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .testTag("dft_fault_kpi_section"),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FaultKpiTile(
      label = "Total Faults",
      value = kpi.totalFaults.toString(),
      badge = kpi.totalStatus,
      cardTag = "dft_kpi_total_faults_card",
      valueTag = "dft_kpi_total_faults_value",
      statusTag = "dft_kpi_total_faults_status",
      modifier = Modifier.weight(1f),
    )
    FaultKpiTile(
      label = "Fault Coverage",
      value = if (kpi.totalFaults > 0) "${"%.1f".format(Locale.US, kpi.faultCoveragePct)}%" else "N/A",
      badge = kpi.coverageStatus,
      cardTag = "dft_kpi_fault_coverage_card",
      valueTag = "dft_kpi_fault_coverage_value",
      statusTag = "dft_kpi_fault_coverage_status",
      modifier = Modifier.weight(1f),
    )
    FaultKpiTile(
      label = "Untestable",
      value = kpi.untestableFaults.toString(),
      badge = kpi.untestableStatus,
      cardTag = "dft_kpi_untestable_faults_card",
      valueTag = "dft_kpi_untestable_faults_value",
      statusTag = "dft_kpi_untestable_faults_status",
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun FaultKpiTile(
  label: String,
  value: String,
  badge: FaultKpiBadge,
  cardTag: String,
  valueTag: String,
  statusTag: String,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val statusColor: Color
  val statusContainer: Color
  when (badge.tier) {
    FaultKpiStatusTier.PASS -> {
      statusColor = eda.success
      statusContainer = eda.successContainer
    }
    FaultKpiStatusTier.INFO -> {
      statusColor = eda.cyanHighlight
      statusContainer = eda.cyanContainer
    }
    FaultKpiStatusTier.WARNING -> {
      statusColor = eda.warning
      statusContainer = eda.warningContainer
    }
    FaultKpiStatusTier.CRITICAL -> {
      statusColor = eda.error
      statusContainer = eda.errorContainer
    }
    FaultKpiStatusTier.NEUTRAL -> {
      statusColor = MaterialTheme.colorScheme.onSurfaceVariant
      statusContainer = MaterialTheme.colorScheme.surfaceVariant
    }
  }

  Surface(
    color = eda.elevatedCard,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(
      width = 1.dp,
      color = if (badge.tier == FaultKpiStatusTier.NEUTRAL) {
        eda.subtleBorder.copy(alpha = 0.85f)
      } else {
        statusColor.copy(alpha = 0.42f)
      },
    ),
    modifier = modifier.testTag(cardTag),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = label.uppercase(Locale.US),
          style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
        Box(
          modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(statusColor),
        )
      }

      Text(
        text = value,
        style = MaterialTheme.typography.titleMedium.copy(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
        ),
        color = if (badge.tier == FaultKpiStatusTier.PASS || badge.tier == FaultKpiStatusTier.WARNING || badge.tier == FaultKpiStatusTier.CRITICAL) {
          statusColor
        } else {
          MaterialTheme.colorScheme.onSurface
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(valueTag),
      )

      EdaStatusPill(
        text = badge.label,
        color = statusColor,
        containerColor = statusContainer,
        showDot = true,
        modifier = Modifier.testTag(statusTag),
      )
    }
  }
}
