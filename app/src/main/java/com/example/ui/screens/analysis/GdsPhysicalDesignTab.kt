package com.example.ui.screens.analysis

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.synth.FlowStageId
import com.example.domain.synth.FlowStageState
import com.example.domain.synth.FlowStageStatus
import com.example.domain.synth.PhysicalLayoutSnapshot
import com.example.domain.synth.SynthesisReport
import com.example.ui.components.EdaCompactMetric
import com.example.ui.components.EdaKeyValueRow
import com.example.ui.components.EdaPanelHeader
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.components.GdsLayoutViewer
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

private enum class PhysicalMilestoneStatus(val label: String) {
  COMPLETE("COMPLETE"),
  PENDING("NOT RUN"),
  RUNNING("RUNNING"),
  FAILED("FAILED"),
}

private data class PhysicalMilestone(
  val name: String,
  val detail: String,
  val status: PhysicalMilestoneStatus,
)

@Composable
fun GdsPhysicalDesignTab(
  report: SynthesisReport?,
  layout: PhysicalLayoutSnapshot?,
  flowStages: List<FlowStageState>,
  onOpenSynthesis: () -> Unit,
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current

  var pendingGdsBytes by remember { mutableStateOf<ByteArray?>(null) }
  val gdsExportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
  ) { uri: Uri? ->
    val bytes = pendingGdsBytes
    pendingGdsBytes = null
    if (uri != null && bytes != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
      }.onSuccess {
        onNotify("Exported GDSII stream (${bytes.size} bytes).")
      }.onFailure { err ->
        onNotify("GDSII export failed: ${err.localizedMessage ?: "error"}")
      }
    }
  }

  val milestones = remember(report, layout, flowStages) {
    fun stageStatus(id: FlowStageId): PhysicalMilestoneStatus {
      val s = flowStages.firstOrNull { it.id == id }?.status
      return when (s) {
        FlowStageStatus.PASSED -> PhysicalMilestoneStatus.COMPLETE
        FlowStageStatus.RUNNING -> PhysicalMilestoneStatus.RUNNING
        FlowStageStatus.FAILED -> PhysicalMilestoneStatus.FAILED
        else -> if (layout != null) PhysicalMilestoneStatus.COMPLETE else PhysicalMilestoneStatus.PENDING
      }
    }
    listOf(
      PhysicalMilestone("1. Logic Synthesis", report?.let { "${it.cellCount} cells mapped" } ?: "Not run", if (report != null) PhysicalMilestoneStatus.COMPLETE else stageStatus(FlowStageId.SYNTHESIS)),
      PhysicalMilestone("2. Floorplanning", layout?.let { "${"%.1f".format(Locale.US, it.dieWidthUm)}×${"%.1f".format(Locale.US, it.dieHeightUm)} µm · ${it.rowCount} rows" } ?: "Pending PnR", stageStatus(FlowStageId.FLOORPLAN)),
      PhysicalMilestone("3. Standard-Cell Placement", layout?.let { "${it.placedCells.size} placed cells (${"%.1f".format(Locale.US, it.actualUtilizationPercent)}%)" } ?: "Pending PnR", stageStatus(FlowStageId.PLACEMENT)),
      PhysicalMilestone("4. Clock Tree Synthesis (CTS)", layout?.let { "Skew ${"%.3f".format(Locale.US, it.clockSkewNs)} ns · Hold ${formatSignedNs(it.holdWnsNs)}" } ?: "Pending PnR", stageStatus(FlowStageId.CTS)),
      PhysicalMilestone("5. Global & Detail Routing", layout?.let { "${it.wireSegments.size} segments · ${it.viaCount} vias · WL ${"%.1f".format(Locale.US, it.totalWirelengthUm)} µm" } ?: "Pending PnR", stageStatus(FlowStageId.ROUTING)),
      PhysicalMilestone("6. DRC & LVS Signoff", layout?.let { "${it.drcViolations} DRC · LVS ${if (it.lvsClean) "CLEAN" else "MISMATCH"}" } ?: "Pending PnR", stageStatus(FlowStageId.SIGNOFF_DRC_LVS)),
      PhysicalMilestone("7. GDSII Stream-Out", layout?.let { "${it.gdsBytes.size} B · SHA ${it.gdsSha256.take(10)}" } ?: "Pending PnR", if (layout != null) PhysicalMilestoneStatus.COMPLETE else PhysicalMilestoneStatus.PENDING),
    )
  }

  LazyColumn(
    modifier = modifier.fillMaxWidth().testTag("analysis_gds_list"),
    contentPadding = PaddingValues(bottom = 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (layout != null) {
      // 1. Show GDS Layout Viewer 1st when GDSII layout is available
      item {
        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = MaterialTheme.shapes.medium,
          border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.7f)),
          modifier = Modifier.fillMaxWidth().height(500.dp).testTag("analysis_gds_viewer_card"),
        ) {
          Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            GdsLayoutViewer(
              layout = layout,
              modifier = Modifier.fillMaxSize(),
            )
          }
        }
      }

      val dieArea = layout.dieWidthUm * layout.dieHeightUm
      val coreArea = layout.coreWidthUm * layout.coreHeightUm
      val aspectRatio = if (layout.dieHeightUm > 1e-3) layout.dieWidthUm / layout.dieHeightUm else 1.0
      val nonPortCells = layout.placedCells.count { !it.gateType.isPort }
      val seqCells = layout.placedCells.count { it.gateType.isSequential }
      val estCongestionPct = ((layout.wireSegments.size.toDouble() / (nonPortCells.coerceAtLeast(1) * 4.0)) * 100.0)
        .coerceIn(4.0, 96.0)
      val estCtsBuffers = if (seqCells > 0) ((seqCells + 5) / 6).coerceAtLeast(1) else 0

      // 2. Floorplan, Placement, CTS & Routing Summary
      item {
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_gds_metrics_card")) {
          Column {
            EdaPanelHeader(
              title = "Floorplan, CTS & Routing Metrics",
              subtitle = "Die ${"%.1f".format(Locale.US, layout.dieWidthUm)}×${"%.1f".format(Locale.US, layout.dieHeightUm)} µm · AR ${"%.2f".format(Locale.US, aspectRatio)}:1",
              icon = Icons.Rounded.Layers,
            )
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EdaCompactMetric("Die Area", "${"%.1f".format(Locale.US, dieArea)} µm²", modifier = Modifier.weight(1f))
                EdaCompactMetric("Core Area", "${"%.1f".format(Locale.US, coreArea)} µm²", modifier = Modifier.weight(1f))
                EdaCompactMetric("Density", "${"%.1f".format(Locale.US, layout.actualUtilizationPercent)}%", valueColor = eda.cyanHighlight, modifier = Modifier.weight(1f))
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EdaCompactMetric("Wirelength", "${"%.1f".format(Locale.US, layout.totalWirelengthUm)} µm", modifier = Modifier.weight(1f))
                EdaCompactMetric("Congestion Est", "${"%.0f".format(Locale.US, estCongestionPct)}%", modifier = Modifier.weight(1f))
                EdaCompactMetric(
                  label = "DRC Errors",
                  value = layout.drcViolations.toString(),
                  valueColor = if (layout.drcViolations == 0) eda.success else eda.error,
                  modifier = Modifier.weight(1f),
                )
              }
              HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
              EdaKeyValueRow("Placement Rows / Site Utilization", "${layout.rowCount} std-cell rows · target ${layout.coreUtilizationPercent}%")
              EdaKeyValueRow("Clock Tree Sinks / Est. Buffers", "$seqCells FFs · $estCtsBuffers CTS buffers · skew ${"%.3f".format(Locale.US, layout.clockSkewNs)} ns")
              EdaKeyValueRow("Detail Routing Segments / Vias", "${layout.wireSegments.size} segments · ${layout.viaCount} vias")
              EdaKeyValueRow("LVS & GDS Verification", "${if (layout.lvsClean) "LVS Clean" else "LVS Check Required"} · ${layout.drcSummary}")
              EdaKeyValueRow("GDSII Stream Checksum", "SHA-256 ${layout.gdsSha256.take(20)}… (${layout.gdsBytes.size} B)")

              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                  onClick = {
                    pendingGdsBytes = layout.gdsBytes
                    gdsExportLauncher.launch("${layout.moduleName}.gds")
                  },
                  modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("gds_export_stream_button"),
                ) {
                  Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("Export .gds", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                  onClick = {
                    clipboard.setText(AnnotatedString(layout.tclScript))
                    onNotify("Copied OpenROAD / PnR TCL script to clipboard.")
                  },
                  modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("gds_copy_tcl_button"),
                ) {
                  Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("Copy PnR TCL", style = MaterialTheme.typography.labelMedium)
                }
              }
            }
          }
        }
      }
    }

    // 3 (or 1 when NOT RUN). Honest Physical Flow Progress & Status
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_gds_progress_card")) {
        Column {
          EdaPanelHeader(
            title = "Physical Design & GDSII Flow",
            subtitle = if (layout != null) "${layout.moduleName} · ${layout.library.shortName} Signoff" else "PnR & GDSII Stream-Out Status",
            icon = Icons.Rounded.GridView,
            trailing = {
              EdaStatusPill(
                text = if (layout != null) "GDS READY" else "NOT RUN",
                color = if (layout != null) eda.success else eda.warning,
                containerColor = if (layout != null) eda.successContainer else eda.warningContainer,
                showDot = true,
              )
            },
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            milestones.forEach { m ->
              val (mColor, mIcon) = when (m.status) {
                PhysicalMilestoneStatus.COMPLETE -> eda.success to Icons.Rounded.CheckCircle
                PhysicalMilestoneStatus.RUNNING -> eda.warning to Icons.Rounded.Schedule
                PhysicalMilestoneStatus.FAILED -> eda.error to Icons.Rounded.Schedule
                PhysicalMilestoneStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Rounded.Schedule
              }
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  modifier = Modifier.weight(1f).padding(end = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(mIcon, contentDescription = null, tint = mColor, modifier = Modifier.size(15.dp))
                  Column {
                    Text(m.name, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text(m.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
                Text(
                  text = m.status.label,
                  style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  color = mColor,
                )
              }
            }
            if (layout == null) {
              Spacer(Modifier.height(4.dp))
              Button(
                onClick = onOpenSynthesis,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gds_run_pnr_button"),
              ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Run RTL-to-GDSII in Synthesis", style = MaterialTheme.typography.labelLarge)
              }
            }
          }
        }
      }
    }
  }
}
