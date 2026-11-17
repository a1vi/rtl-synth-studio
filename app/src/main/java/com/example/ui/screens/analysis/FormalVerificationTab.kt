package com.example.ui.screens.analysis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.synth.ComparePointVerdict
import com.example.domain.synth.FormalEquivalenceReport
import com.example.domain.synth.KeyPointMatchStatus
import com.example.domain.synth.LecComparePointResult
import com.example.domain.synth.LecConfig
import com.example.domain.synth.LecOverallStatus
import com.example.domain.synth.SynthesisReport
import com.example.ui.components.EdaCompactMetric
import com.example.ui.components.EdaKeyValueRow
import com.example.ui.components.EdaPanelHeader
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

private enum class LecPointFilter(val label: String) {
  ALL("All"),
  NON_EQ("Non-Eq"),
  PROVEN("Proven"),
  BOUNDED("Bounded"),
  INCONCL("Inconcl"),
  UNMAPPED("Unmapped"),
}

@Composable
fun FormalVerificationTab(
  report: SynthesisReport,
  lecReport: FormalEquivalenceReport?,
  lecConfig: LecConfig,
  isRunningLec: Boolean,
  isResultOutdated: Boolean,
  onUpdateLecConfig: ((LecConfig) -> LecConfig) -> Unit,
  onRunLec: (String?) -> Unit,
  onLocateConeInSchematic: (Set<String>) -> Unit,
  onJumpToRtlLine: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  var filter by rememberSaveable { mutableStateOf(LecPointFilter.ALL) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var expandedPointId by rememberSaveable { mutableStateOf<String?>(null) }
  var showCustomNetlist by rememberSaveable { mutableStateOf(false) }
  var customNetlistDraft by rememberSaveable(report.moduleName) {
    mutableStateOf(lecConfig.customRevisedNetlistOverride.orEmpty())
  }

  val filteredPoints = remember(lecReport, filter, searchQuery) {
    val pts = lecReport?.comparePoints.orEmpty()
    val q = searchQuery.trim().lowercase(Locale.ROOT)
    pts.filter { pt ->
      val fOk = when (filter) {
        LecPointFilter.ALL -> true
        LecPointFilter.NON_EQ -> pt.verdict == ComparePointVerdict.NON_EQUIVALENT
        LecPointFilter.PROVEN -> pt.verdict == ComparePointVerdict.PROVEN_EQUIVALENT
        LecPointFilter.BOUNDED -> pt.verdict == ComparePointVerdict.BOUNDED_EQUIVALENT
        LecPointFilter.INCONCL -> pt.verdict == ComparePointVerdict.INCONCLUSIVE
        LecPointFilter.UNMAPPED -> pt.matchStatus != KeyPointMatchStatus.MATCHED
      }
      val qOk = q.isEmpty() ||
        pt.name.lowercase(Locale.ROOT).contains(q) ||
        pt.kind.shortTag.lowercase(Locale.ROOT).contains(q) ||
        pt.diagnosticReason.lowercase(Locale.ROOT).contains(q)
      fOk && qOk
    }
  }

  LazyColumn(
    modifier = modifier.fillMaxWidth().testTag("analysis_lec_list"),
    contentPadding = PaddingValues(bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // 1. Setup & Run Control Card
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("lec_control_card")) {
        Column {
          EdaPanelHeader(
            title = "Formal Logic Equivalence (LEC)",
            subtitle = "Golden RTL vs. Revised Gate-Level Netlist",
            icon = Icons.AutoMirrored.Rounded.FactCheck,
            trailing = {
              val status = lecReport?.overallStatus ?: LecOverallStatus.UNAVAILABLE
              val (pillColor, pillBg) = when (status) {
                LecOverallStatus.EQUIVALENT -> eda.success to eda.successContainer
                LecOverallStatus.NON_EQUIVALENT -> eda.error to eda.errorContainer
                LecOverallStatus.INCONCLUSIVE -> eda.warning to eda.warningContainer
                LecOverallStatus.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant to eda.elevatedCard
              }
              EdaStatusPill(
                text = if (lecReport == null) "NOT RUN" else status.badge,
                color = pillColor,
                containerColor = pillBg,
                showDot = true,
                modifier = Modifier.testTag("lec_status_pill"),
              )
            },
          )

          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (isResultOutdated) {
              Surface(
                color = eda.warningContainer.copy(alpha = 0.65f),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, eda.warning.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = eda.warning, modifier = Modifier.size(16.dp))
                  Text(
                    text = "RTL edited since last synthesis — LEC checks live Golden RTL against prior synthesized netlist.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                }
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Constrain DFT Scan (SE = 0)", style = MaterialTheme.typography.labelLarge)
                Text(
                  text = "Treat scan_en = 0 & ignore scan test ports during functional verify",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = lecConfig.constrainScanEnableZero,
                onCheckedChange = { checked ->
                  onUpdateLecConfig { it.copy(constrainScanEnableZero = checked) }
                },
                modifier = Modifier.testTag("lec_scan_constraint_switch"),
              )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Bounded Sequential Depth (BMC Cycles)", style = MaterialTheme.typography.labelMedium)
              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                listOf(16, 24, 32, 64).forEach { depth ->
                  FilterChip(
                    selected = lecConfig.bmcCycleDepth == depth,
                    onClick = { onUpdateLecConfig { it.copy(bmcCycleDepth = depth) } },
                    label = {
                      Text(
                        text = "$depth cyc",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                      )
                    },
                    modifier = Modifier.testTag("lec_bmc_depth_$depth"),
                  )
                }
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              TextButton(
                onClick = { showCustomNetlist = !showCustomNetlist },
                modifier = Modifier.heightIn(min = 48.dp).testTag("lec_custom_netlist_toggle"),
              ) {
                Icon(
                  imageVector = if (showCustomNetlist) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                  text = if (customNetlistDraft.isNotBlank()) "Custom Netlist Active" else "Verify Edited / Custom Netlist",
                  style = MaterialTheme.typography.labelMedium,
                )
              }

              Button(
                onClick = {
                  onRunLec(if (showCustomNetlist || customNetlistDraft.isNotBlank()) customNetlistDraft else null)
                },
                enabled = !isRunningLec,
                modifier = Modifier.heightIn(min = 48.dp).testTag("lec_run_button"),
              ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (isRunningLec) "Checking..." else "Run Formal LEC", style = MaterialTheme.typography.labelLarge)
              }
            }

            AnimatedVisibility(visible = showCustomNetlist) {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = "Revised Gate-Level Verilog Override",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                      onClick = { customNetlistDraft = report.netlistVerilog },
                      modifier = Modifier.heightIn(min = 40.dp).testTag("lec_load_active_netlist_button"),
                    ) {
                      Text("Load Synthesized", style = MaterialTheme.typography.labelSmall)
                    }
                    if (customNetlistDraft.isNotBlank()) {
                      TextButton(
                        onClick = {
                          customNetlistDraft = ""
                          onUpdateLecConfig { it.copy(customRevisedNetlistOverride = null) }
                        },
                        modifier = Modifier.heightIn(min = 40.dp).testTag("lec_clear_custom_netlist_button"),
                      ) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                      }
                    }
                  }
                }
                OutlinedTextField(
                  value = customNetlistDraft,
                  onValueChange = { customNetlistDraft = it },
                  placeholder = { Text("Paste or mutate structural gate-level Verilog netlist (e.g., swap AND2 -> OR2)...") },
                  minLines = 4,
                  maxLines = 8,
                  textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                  modifier = Modifier.fillMaxWidth().testTag("lec_custom_netlist_input"),
                )
              }
            }
          }
        }
      }
    }

    // 2. Verdict & KPI Summary Card
    if (lecReport != null) {
      item {
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("lec_summary_card")) {
          Column {
            EdaPanelHeader(
              title = lecReport.overallStatus.label,
              subtitle = lecReport.setupSummary,
              icon = Icons.Rounded.VerifiedUser,
            )
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EdaCompactMetric("Compare Pts", lecReport.totalComparePoints.toString(), modifier = Modifier.weight(1f))
                EdaCompactMetric(
                  label = "Proven (2^N)",
                  value = lecReport.provenCount.toString(),
                  valueColor = eda.success,
                  modifier = Modifier.weight(1f),
                )
                EdaCompactMetric(
                  label = "Bounded Eq",
                  value = lecReport.boundedEquivalentCount.toString(),
                  valueColor = eda.cyanHighlight,
                  modifier = Modifier.weight(1f),
                )
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EdaCompactMetric(
                  label = "Non-Equiv",
                  value = lecReport.nonEquivalentCount.toString(),
                  valueColor = if (lecReport.nonEquivalentCount > 0) eda.error else MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.weight(1f),
                )
                EdaCompactMetric(
                  label = "Inconclusive",
                  value = lecReport.inconclusiveCount.toString(),
                  valueColor = if (lecReport.inconclusiveCount > 0) eda.warning else MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.weight(1f),
                )
                EdaCompactMetric(
                  label = "Runtime",
                  value = "${lecReport.executionDurationMs} ms",
                  modifier = Modifier.weight(1f),
                )
              }

              HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
              EdaKeyValueRow("Ports (Golden / Revised)", "${lecReport.goldenPortCount} / ${lecReport.revisedPortCount}")
              EdaKeyValueRow("Matched Key Points", "${lecReport.matchedCount} mapped · ${lecReport.unmappedGoldenCount + lecReport.unmappedRevisedCount} unmapped")

              if (lecReport.abstractionReasons.isNotEmpty()) {
                Surface(
                  color = eda.warningContainer.copy(alpha = 0.5f),
                  shape = MaterialTheme.shapes.small,
                  border = BorderStroke(1.dp, eda.warning.copy(alpha = 0.45f)),
                  modifier = Modifier.fillMaxWidth().testTag("lec_abstraction_notice"),
                ) {
                  Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    Text(
                      text = "Honest Abstraction Disclosure (${lecReport.abstractionReasons.size})",
                      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                      color = eda.warning,
                    )
                    lecReport.abstractionReasons.take(4).forEach { reason ->
                      Text(
                        text = "• $reason",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      // 3. Compare Points Header, Search & Filters
      item(key = "lec_compare_points_header") {
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("lec_compare_points_card")) {
          Column {
            EdaPanelHeader(
              title = "Compare Points & Counterexamples",
              subtitle = "${filteredPoints.size} of ${lecReport.comparePoints.size} compare point(s)",
              icon = Icons.AutoMirrored.Rounded.FactCheck,
            )
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search compare points or diagnostics...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("lec_point_search_input"),
              )

              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                LecPointFilter.entries.forEach { f ->
                  FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("lec_filter_${f.name.lowercase()}"),
                  )
                }
              }

              if (filteredPoints.isEmpty()) {
                Text(
                  text = "No compare points match the current filter.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      if (filteredPoints.isNotEmpty()) {
        items(
          count = filteredPoints.size,
          key = { idx -> "lec_pt_${filteredPoints[idx].id}" },
        ) { idx ->
          val pt = filteredPoints[idx]
          ComparePointRowCard(
            point = pt,
            expanded = expandedPointId == pt.id || pt.verdict == ComparePointVerdict.NON_EQUIVALENT,
            onToggleExpand = {
              expandedPointId = if (expandedPointId == pt.id) null else pt.id
            },
            onLocateInSchematic = {
              if (pt.netlistNodeIds.isNotEmpty()) {
                onLocateConeInSchematic(pt.netlistNodeIds.toSet())
              }
            },
            onJumpToRtlLine = {
              if (pt.rtlSourceLine > 0) onJumpToRtlLine(pt.rtlSourceLine)
            },
          )
        }
      }

      // 4. Transcript Log
      item {
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("lec_log_card")) {
          Column {
            EdaPanelHeader(
              title = "Formal Proof Transcript",
              subtitle = "${lecReport.logLines.size} log entries",
              icon = Icons.Rounded.Terminal,
            )
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .background(eda.codeSurface)
                .padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
              lecReport.logLines.forEach { line ->
                Text(
                  text = line,
                  style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  color = when {
                    "[FAIL]" in line || "[ERROR]" in line -> eda.error
                    "[WARN]" in line || "[LEC-NOTE]" in line -> eda.warning
                    "[LEC-DONE]" in line -> eda.cyanHighlight
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                  },
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
private fun ComparePointRowCard(
  point: LecComparePointResult,
  expanded: Boolean,
  onToggleExpand: () -> Unit,
  onLocateInSchematic: () -> Unit,
  onJumpToRtlLine: () -> Unit,
) {
  val eda = LocalEdaColors.current
  val (vColor, vBg) = when (point.verdict) {
    ComparePointVerdict.PROVEN_EQUIVALENT -> eda.success to eda.successContainer
    ComparePointVerdict.BOUNDED_EQUIVALENT -> eda.cyanHighlight to eda.cyanContainer
    ComparePointVerdict.NON_EQUIVALENT -> eda.error to eda.errorContainer
    ComparePointVerdict.INCONCLUSIVE -> eda.warning to eda.warningContainer
  }

  Surface(
    color = eda.elevatedCard,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(
      width = if (point.verdict == ComparePointVerdict.NON_EQUIVALENT) 1.5.dp else 1.dp,
      color = if (point.verdict == ComparePointVerdict.NON_EQUIVALENT) eda.error.copy(alpha = 0.7f) else eda.subtleBorder.copy(alpha = 0.5f),
    ),
    modifier = Modifier
      .fillMaxWidth()
      .clip(MaterialTheme.shapes.small)
      .testTag("lec_point_${point.name}"),
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(MaterialTheme.shapes.small)
          .clickable(onClick = onToggleExpand),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier.weight(1f).padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            EdaStatusPill(
              text = point.kind.shortTag,
              color = eda.cyanHighlight,
              containerColor = eda.cyanContainer,
            )
            Text(
              text = "${point.name}[${point.bitWidth}b]",
              style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.SemiBold,
              ),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          EdaStatusPill(
            text = point.verdict.badge,
            color = vColor,
            containerColor = vBg,
            showDot = true,
          )
        }

        Text(
          text = "${point.matchStatus.label} · ${point.proofMethod.label} · ${point.coneGateCount} cone cell(s) · ${point.inputSupportBits}b support",
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.4f))
          Text(
            text = point.diagnosticReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
          )

          val cex = point.counterexample
          if (cex != null) {
            Surface(
              color = eda.errorContainer.copy(alpha = 0.45f),
              shape = MaterialTheme.shapes.small,
              border = BorderStroke(1.dp, eda.error.copy(alpha = 0.55f)),
              modifier = Modifier.fillMaxWidth().testTag("lec_counterexample_${point.name}"),
            ) {
              Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text = "Counterexample Trace (Cycle ${cex.cycleIndex})",
                  style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                  ),
                  color = eda.error,
                )
                EdaKeyValueRow("Golden RTL Output", "${cex.goldenValueHex} (${cex.goldenValueBin}b)", valueColor = eda.success)
                EdaKeyValueRow("Revised Netlist Out", "${cex.revisedValueHex} (${cex.revisedValueBin}b)", valueColor = eda.error)
                if (cex.stimulusInputs.isNotEmpty()) {
                  val stimStr = cex.stimulusInputs.entries.joinToString(", ") { "${it.key}=${it.value}" }
                  Text(
                    text = "Stimulus: $stimStr",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                }
              }
            }
          }

          if (point.netlistNodeIds.isNotEmpty() || point.rtlSourceLine > 0) {
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              if (point.netlistNodeIds.isNotEmpty()) {
                OutlinedButton(
                  onClick = onLocateInSchematic,
                  modifier = Modifier.heightIn(min = 48.dp).testTag("lec_locate_${point.name}"),
                ) {
                  Icon(Icons.Rounded.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(4.dp))
                  Text("Locate Cone (${point.netlistNodeIds.size})", style = MaterialTheme.typography.labelSmall)
                }
              }
              if (point.rtlSourceLine > 0) {
                OutlinedButton(
                  onClick = onJumpToRtlLine,
                  modifier = Modifier.heightIn(min = 48.dp).testTag("lec_rtl_${point.name}"),
                ) {
                  Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(4.dp))
                  Text("RTL Line ${point.rtlSourceLine}", style = MaterialTheme.typography.labelSmall)
                }
              }
            }
          }
        }
      }
    }
  }
}
