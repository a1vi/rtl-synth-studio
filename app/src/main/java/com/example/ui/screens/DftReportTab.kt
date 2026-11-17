package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.synth.DftReport
import com.example.domain.synth.DftRuleSeverity
import com.example.domain.synth.ScanChainStitcher
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.VerilogNetlistScanResult
import com.example.ui.components.AtpgCoverageRampCard
import com.example.ui.components.DftFaultAnalysisCard
import com.example.ui.components.EdaCompactMetric
import com.example.ui.components.EdaSectionHeader
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.components.TestCoverageVisualizationSection
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.IconButton
import com.example.domain.synth.DftScanCell

@Composable
fun DftReportTab(
  report: SynthesisReport,
  dft: DftReport,
  onRegenerateAtpg: (Int) -> Unit,
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
  onEnableScanInsertion: () -> Unit = {},
  onLocateScanCellInSchematic: (String) -> Unit = {},
) {
  val eda = LocalEdaColors.current
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current

  var selectedPatternCap by rememberSaveable(dft.config.atpgMaxPatterns) {
    mutableIntStateOf(dft.config.atpgMaxPatterns)
  }
  var atpgHexMode by rememberSaveable { mutableStateOf(false) }
  var inspectedScanCell by remember(dft.scanChains) { mutableStateOf<DftScanCell?>(null) }
  var artifactTab by rememberSaveable { mutableIntStateOf(0) } // 0=STIL, 1=SCANDEF, 2=Verilog

  var pendingExport by remember { mutableStateOf<String?>(null) }
  val saveStilLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain"),
  ) { uri: Uri? ->
    val payload = pendingExport
    pendingExport = null
    if (uri != null && payload != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
          out.write(payload.toByteArray(Charsets.UTF_8))
        }
      }.onSuccess {
        onNotify("Exported DFT artifact successfully.")
      }.onFailure { err ->
        onNotify("Export failed: ${err.localizedMessage ?: "error"}")
      }
    }
  }

  val covColor = when {
    dft.stuckAtCoveragePct >= 90.0 -> eda.success
    dft.stuckAtCoveragePct >= 65.0 -> eda.cyanHighlight
    else -> eda.warning
  }

  LazyColumn(
    modifier = modifier.fillMaxSize().testTag("dft_report_list"),
    contentPadding = PaddingValues(vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // 1. Fault Coverage & Scan Summary KPI Card
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("dft_kpi_card")) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Text(
                text = "DFT & ATPG Testability",
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = if (dft.scanInserted) {
                  "${dft.config.scanStyle.displayName} · Shift @ ${"%.1f".format(Locale.US, dft.scanShiftFreqMhz)} MHz"
                } else {
                  "Passive DRC & Stuck-At Fault Audit"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            EdaStatusPill(
              text = if (dft.scanInserted) "SCAN ACTIVE" else "AUDIT ONLY",
              color = if (dft.scanInserted) eda.success else eda.warning,
              containerColor = if (dft.scanInserted) eda.successContainer else eda.warningContainer,
              showDot = true,
            )
          }

          TestCoverageVisualizationSection(
            dft = dft,
            onEnableScanInsertion = onEnableScanInsertion,
          )

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EdaCompactMetric(
              label = "Faults Det.",
              value = "${dft.detectedFaults}/${dft.totalFaults}",
              valueColor = covColor,
              modifier = Modifier.weight(1f),
            )
            EdaCompactMetric(
              label = "Scan Flops",
              value = "${dft.scanReplacedElements}/${dft.totalSeqElements}",
              valueColor = eda.cyanHighlight,
              modifier = Modifier.weight(1f),
            )
            EdaCompactMetric(
              label = "Area +%",
              value = "+${"%.1f".format(Locale.US, dft.scanAreaOverheadPct)}%",
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }

    // 2. ATPG Cumulative Test Coverage Growth Chart
    item {
      AtpgCoverageRampCard(dft = dft)
    }

    // 3. DFT Rule Checker (DRC-DFT) — Clean divider-separated rows with collapsible details
    item {
      val passCount = dft.dftRuleChecks.count { it.severity == DftRuleSeverity.PASS }
      val hasIssues = passCount < dft.dftRuleChecks.size
      var showAllRules by rememberSaveable { mutableStateOf(hasIssues) }

      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("dft_rule_checks_card")) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            EdaSectionHeader(
              title = "DFT Rule Checker",
              subtitle = "$passCount/${dft.dftRuleChecks.size} signoff rules passed",
              modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            TextButton(
              onClick = { showAllRules = !showAllRules },
              modifier = Modifier.heightIn(min = 48.dp),
            ) {
              Text(if (showAllRules) "Collapse" else "Details", style = MaterialTheme.typography.labelMedium)
              Icon(
                imageVector = if (showAllRules) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
            }
          }

          val visibleRules = if (showAllRules) {
            dft.dftRuleChecks
          } else {
            dft.dftRuleChecks.filter { it.severity != DftRuleSeverity.PASS }
          }

          if (visibleRules.isEmpty()) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = eda.success, modifier = Modifier.size(18.dp))
              Text(
                text = "All ${dft.dftRuleChecks.size} clock, reset, loop & lockup rules passed cleanly.",
                style = MaterialTheme.typography.bodySmall,
                color = eda.success,
              )
            }
          } else {
            visibleRules.forEachIndexed { idx, rule ->
              val (icon, tint) = when (rule.severity) {
                DftRuleSeverity.PASS -> Icons.Rounded.CheckCircle to eda.success
                DftRuleSeverity.WARNING -> Icons.Rounded.WarningAmber to eda.warning
                DftRuleSeverity.VIOLATION -> Icons.Rounded.ErrorOutline to eda.error
              }
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
              ) {
                Icon(icon, contentDescription = rule.severity.name, tint = tint, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text = "${rule.ruleId} · ${rule.title}",
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = FontWeight.SemiBold,
                      modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Text(
                      text = rule.severity.name,
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                      color = tint,
                    )
                  }
                  Text(
                    text = rule.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              if (idx < visibleRules.lastIndex) {
                HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.35f))
              }
            }
          }
        }
      }
    }

    // 4. Scan Chain Topology & Port Stitcher
    item {
      var stitchedPreviewResult by remember(report.netlistVerilog, dft.scanInserted) {
        mutableStateOf<VerilogNetlistScanResult?>(null)
      }
      var expandChainNodes by rememberSaveable { mutableStateOf(false) }

      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("dft_scan_chains_card")) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            EdaSectionHeader(
              title = "Scan Chain Topology",
              subtitle = if (dft.scanChains.isNotEmpty()) {
                "${dft.scanChains.size} chain(s) · ${dft.lockupLatchCount} lockup latch(es)"
              } else {
                "Auto-insert SDFF chains and scan ports"
              },
              modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            if (dft.scanChains.isNotEmpty()) {
              val continuityBadge = remember(report.ports, report.nodes, dft.scanChains, dft.config) {
                ScanChainStitcher.verifyChainContinuity(
                  ports = report.ports,
                  nodes = report.nodes,
                  chains = dft.scanChains,
                  config = dft.config,
                )
              }
              EdaStatusPill(
                text = if (continuityBadge.isValid) "VERIFIED (${continuityBadge.totalStitchedBits}b)" else "ISSUE",
                color = if (continuityBadge.isValid) eda.success else eda.error,
                containerColor = if (continuityBadge.isValid) eda.successContainer else eda.errorContainer,
                showDot = true,
              )
            }
          }

          if (dft.scanChains.isEmpty()) {
            Text(
              text = "Scan chains are currently disabled. Enable automatic scan insertion to convert DFF/DFFR registers into SDFF/SDFFR cells with scan_en, scan_in_i, and scan_out_i ports.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (dft.totalSeqElements > 0) {
              Button(
                onClick = onEnableScanInsertion,
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 48.dp)
                  .testTag("dft_auto_insert_chains_button"),
              ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Auto-Insert Scan Chains (${dft.totalSeqElements} FFs)", style = MaterialTheme.typography.labelLarge)
              }
            }
          } else {
            val continuity = remember(report.ports, report.nodes, dft.scanChains, dft.config) {
              ScanChainStitcher.verifyChainContinuity(
                ports = report.ports,
                nodes = report.nodes,
                chains = dft.scanChains,
                config = dft.config,
              )
            }
            val portDefinitions = remember(dft.scanChains, dft.jtagTapInserted, dft.mbistCollarInserted) {
              ScanChainStitcher.buildScanPortDefinitions(
                existingPortNames = emptySet(),
                chainCount = dft.scanChains.size,
                includeJtagTap = dft.jtagTapInserted,
                includeMbist = dft.mbistCollarInserted,
              )
            }

            // Clean Port Definitions Table (single soft-tinted block, no heavy border nesting)
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier.fillMaxWidth().testTag("dft_scan_ports_table"),
            ) {
              Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Text(
                  text = "Inserted Scan Ports (${portDefinitions.size})",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                portDefinitions.forEach { pDef ->
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text = pDef.toAnsiPortFragment(),
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                      color = eda.cyanHighlight,
                      modifier = Modifier.weight(1f),
                    )
                    Text(
                      text = pDef.chainIndex?.let { "Chain #$it" } ?: pDef.role.name,
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }

            // Concise Chain Cards (collapse noisy per-flop pills behind optional toggle)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Stitched Chains (${dft.scanChains.size})",
                style = MaterialTheme.typography.labelLarge,
              )
              TextButton(
                onClick = { expandChainNodes = !expandChainNodes },
                modifier = Modifier.heightIn(min = 48.dp).testTag("dft_toggle_chain_nodes_button"),
              ) {
                Text(
                  text = if (expandChainNodes) "Compact View" else "Show Flop Order",
                  style = MaterialTheme.typography.labelSmall,
                )
              }
            }

            dft.scanChains.forEachIndexed { idx, chain ->
              Surface(
                color = eda.codeSurface.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("dft_chain_card_${chain.chainIndex}"),
              ) {
                Column(
                  modifier = Modifier.padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text = "Chain #${chain.chainIndex} · ${chain.totalFlipFlopBits}b (${chain.cells.size} cells)",
                      style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                      color = eda.cyanHighlight,
                    )
                    Text(
                      text = "CLK: ${chain.clockDomains.joinToString(",")}",
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Text(
                    text = "${chain.scanInPort}  →  ${chain.cells.count { !it.isLockupLatch }} SDFF" +
                      (if (chain.hasLockupLatch) " + Lockup" else "") +
                      "  →  ${chain.scanOutPort}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                  )

                  AnimatedVisibility(visible = expandChainNodes) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                      horizontalArrangement = Arrangement.spacedBy(6.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      EdaStatusPill(
                        text = chain.scanInPort,
                        color = eda.success,
                        containerColor = eda.successContainer,
                      )
                      chain.cells.forEach { cell ->
                        val isInspected = inspectedScanCell?.nodeId == cell.nodeId &&
                          inspectedScanCell?.orderIndex == cell.orderIndex
                        Text("→", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(
                          onClick = { inspectedScanCell = cell },
                          color = if (isInspected) MaterialTheme.colorScheme.primaryContainer else if (cell.isLockupLatch) eda.warningContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                          shape = MaterialTheme.shapes.small,
                          border = BorderStroke(
                            width = if (isInspected) 1.5.dp else 1.dp,
                            color = if (isInspected) eda.cyanHighlight else eda.subtleBorder.copy(alpha = 0.6f),
                          ),
                          modifier = Modifier.testTag("dft_scan_cell_chip_${chain.chainIndex}_${cell.orderIndex}"),
                        ) {
                          Text(
                            text = if (cell.isLockupLatch) "DLAT:${cell.instanceName}" else "${cell.instanceName}(${cell.bitWidth}b)",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = if (cell.isLockupLatch) eda.warning else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                          )
                        }
                      }
                      Text("→", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                      EdaStatusPill(
                        text = chain.scanOutPort,
                        color = eda.cyanHighlight,
                        containerColor = eda.cyanContainer,
                      )
                    }
                  }
                }
              }
            }

            inspectedScanCell?.let { cell ->
              Surface(
                color = eda.elevatedCard,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, eda.cyanHighlight.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().testTag("dft_inspected_scan_cell_card"),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                      text = "Stage #${cell.orderIndex + 1} · ${cell.instanceName} (${cell.cellName})",
                      style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                      color = eda.cyanHighlight,
                    )
                    Text(
                      text = "Domain: ${cell.clockDomain} · Net: ${cell.outputNet} · ${cell.bitWidth}b${if (cell.isLockupLatch) " (Lockup)" else ""}",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  IconButton(
                    onClick = { onLocateScanCellInSchematic(cell.nodeId) },
                    modifier = Modifier.size(48.dp).testTag("dft_locate_cell_schematic_button"),
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.AccountTree,
                      contentDescription = "Locate scan cell in Schematic",
                      tint = eda.cyanHighlight,
                    )
                  }
                }
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              OutlinedButton(
                onClick = {
                  stitchedPreviewResult = runCatching {
                    ScanChainStitcher.insertScanChainsIntoVerilogNetlist(
                      netlistVerilog = report.netlistVerilog,
                      library = report.targetLibrary,
                      config = dft.config,
                    )
                  }.getOrNull()
                  onNotify("Verified netlist scan stitching (${continuity.verifiedChains}/${continuity.totalChains} chains).")
                },
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp)
                  .testTag("dft_netlist_stitch_button"),
              ) {
                Text("Verify Stitch", style = MaterialTheme.typography.labelMedium)
              }
              OutlinedButton(
                onClick = {
                  val verilogPayload = stitchedPreviewResult?.scanInsertedVerilog ?: report.netlistVerilog
                  clipboard.setText(AnnotatedString(verilogPayload))
                  onNotify("Copied scan-stitched Verilog netlist to clipboard.")
                },
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp)
                  .testTag("dft_copy_scan_netlist_button"),
              ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy Scan .v", style = MaterialTheme.typography.labelMedium)
              }
            }

            stitchedPreviewResult?.let { stitched ->
              Surface(
                color = eda.codeSurface.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("dft_stitched_netlist_result"),
              ) {
                Column(
                  modifier = Modifier.padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Text(
                    text = "Stitch Verification: ${stitched.convertedFlopCount} FFs · ${stitched.lockupLatchCount} lockups · ${if (stitched.continuityReport.isValid) "PASS" else "WARN"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = if (stitched.continuityReport.isValid) eda.success else eda.warning,
                  )
                  Text(
                    text = stitched.scanInsertedVerilog.lines().take(8).joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }
    }

    // 5. ATPG Pattern Generator & Dedicated Fault Analysis Component
    item {
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("dft_atpg_card")) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            EdaSectionHeader(
              title = "ATPG Vector Generator",
              subtitle = "${dft.atpgVectors.size} compacted test patterns · ${dft.totalFaults} stuck-at sites",
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                listOf(16, 32, 64).forEach { cap ->
                  FilterChip(
                    selected = selectedPatternCap == cap,
                    onClick = { selectedPatternCap = cap },
                    label = { Text("Max $cap", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("dft_atpg_cap_$cap"),
                  )
                }
              }
              Spacer(Modifier.width(8.dp))
              Button(
                onClick = { onRegenerateAtpg(selectedPatternCap) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("dft_run_atpg_button"),
              ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Run ATPG", style = MaterialTheme.typography.labelLarge)
              }
            }

            if (dft.atpgVectors.isNotEmpty()) {
              var selectedVectorIdx by rememberSaveable(dft.atpgVectors.size) { mutableIntStateOf(0) }
              val activeVector = dft.atpgVectors.getOrElse(selectedVectorIdx) { dft.atpgVectors.first() }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  dft.atpgVectors.take(12).forEachIndexed { idx, vec ->
                    FilterChip(
                      selected = activeVector.index == vec.index,
                      onClick = { selectedVectorIdx = idx },
                      label = {
                        Text(
                          text = "#${vec.index} (+${vec.newlyDetectedFaults})",
                          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        )
                      },
                      modifier = Modifier.testTag("dft_vector_chip_${vec.index}"),
                    )
                  }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  FilterChip(
                    selected = !atpgHexMode,
                    onClick = { atpgHexMode = false },
                    label = { Text("BIN", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("dft_atpg_format_bin"),
                  )
                  FilterChip(
                    selected = atpgHexMode,
                    onClick = { atpgHexMode = true },
                    label = { Text("HEX", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("dft_atpg_format_hex"),
                  )
                }
              }

              Surface(
                color = eda.codeSurface.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("dft_vector_inspector_card"),
              ) {
                Column(
                  modifier = Modifier.padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text = "Pattern #${activeVector.index} · ${activeVector.targetedAlgorithm}",
                      style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                      color = eda.cyanHighlight,
                    )
                    EdaStatusPill(
                      text = "+${activeVector.newlyDetectedFaults} det",
                      color = eda.success,
                      containerColor = eda.successContainer,
                    )
                  }

                  if (activeVector.chainVectors.isNotEmpty()) {
                    activeVector.chainVectors.forEach { cv ->
                      val siStr = formatBitstream(cv.scanInBitstream, atpgHexMode)
                      val soStr = formatBitstream(cv.expectedScanOutBitstream, atpgHexMode)
                      Text(
                        text = "Shift-In  ${cv.scanInPort} = $siStr",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = eda.success,
                      )
                      Text(
                        text = "Expect-Out ${cv.scanOutPort} = $soStr",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = eda.cyanHighlight,
                      )
                    }
                  } else if (activeVector.scanInBits.isNotEmpty()) {
                    val rawBits = activeVector.scanInBits.values.joinToString("")
                    Text(
                      text = "Scan-In (PPI): ${formatBitstream(rawBits, atpgHexMode)}",
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                      color = eda.success,
                    )
                  }

                  val piStr = activeVector.piBits.entries.joinToString(" ") { "${it.key}=${it.value}" }
                  val poStr = activeVector.expectedPoBits.entries.joinToString(" ") { "${it.key}=${it.value}" }
                  Text(
                    text = "PI : $piStr",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Text(
                    text = "PO : ${poStr.ifBlank { "(scan-only capture)" }}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }

        // 6. Dedicated Fault Analysis Component (Summary Table + Model/Status Filtering)
        DftFaultAnalysisCard(
          dft = dft,
          onLocateNodeInSchematic = onLocateScanCellInSchematic,
        )
      }
    }

    // 6. IEEE 1450 STIL & SCANDEF Pattern Export Card
    item {
      var expandArtifact by rememberSaveable { mutableStateOf(false) }

      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("dft_stil_export_card")) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          EdaSectionHeader(
            title = "STIL, SCANDEF & Scan Netlist",
            subtitle = "ATE vector and physical scan chain exports",
          )
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilterChip(
              selected = artifactTab == 0,
              onClick = { artifactTab = 0 },
              label = { Text("STIL (.stil)", style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.testTag("dft_preview_stil_chip"),
            )
            FilterChip(
              selected = artifactTab == 1,
              onClick = { artifactTab = 1 },
              label = { Text("SCANDEF (.def)", style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.testTag("dft_preview_scandef_chip"),
            )
            FilterChip(
              selected = artifactTab == 2,
              onClick = { artifactTab = 2 },
              label = { Text("Scan Netlist (.v)", style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.testTag("dft_preview_verilog_chip"),
            )
          }
          val activeText = when (artifactTab) {
            0 -> dft.stilPatternText
            1 -> dft.scanDefText
            else -> report.netlistVerilog
          }
          val activeLabel = when (artifactTab) {
            0 -> "STIL"
            1 -> "SCANDEF"
            else -> "Scan Verilog"
          }
          Surface(
            color = eda.codeSurface.copy(alpha = 0.7f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              val lineCap = if (expandArtifact) 28 else 10
              Text(
                text = activeText.lines().take(lineCap).joinToString("\n"),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurface,
              )
              if (activeText.lines().size > 10) {
                TextButton(
                  onClick = { expandArtifact = !expandArtifact },
                  modifier = Modifier.heightIn(min = 48.dp),
                ) {
                  Text(if (expandArtifact) "Show Less" else "Expand Preview", style = MaterialTheme.typography.labelSmall)
                }
              }
            }
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedButton(
              onClick = {
                clipboard.setText(AnnotatedString(activeText))
                onNotify("Copied $activeLabel output to clipboard.")
              },
              modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("dft_copy_artifact_button"),
            ) {
              Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Copy", style = MaterialTheme.typography.labelLarge)
            }
            Button(
              onClick = {
                pendingExport = activeText
                val ext = when (artifactTab) {
                  0 -> "stil"
                  1 -> "def"
                  else -> "v"
                }
                val suffix = if (artifactTab == 2) "_scan_synth" else "_dft"
                saveStilLauncher.launch("${report.moduleName}$suffix.$ext")
              },
              modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("dft_export_stil_button"),
            ) {
              Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(
                text = when (artifactTab) {
                  0 -> "Export .stil"
                  1 -> "Export .def"
                  else -> "Export .v"
                },
                style = MaterialTheme.typography.labelLarge,
              )
            }
          }
        }
      }
    }
  }
}

private fun formatBitstream(rawBits: String, hexMode: Boolean): String {
  if (!hexMode || rawBits.isBlank()) return rawBits
  val clean = rawBits.trim()
  if (clean.any { it != '0' && it != '1' }) return clean
  val paddedLen = ((clean.length + 3) / 4) * 4
  val padded = clean.padStart(paddedLen, '0')
  val hex = buildString {
    for (i in padded.indices step 4) {
      val nibble = padded.substring(i, i + 4).toInt(2)
      append(nibble.toString(16).uppercase(Locale.US))
    }
  }
  return "${clean.length}'h$hex"
}

