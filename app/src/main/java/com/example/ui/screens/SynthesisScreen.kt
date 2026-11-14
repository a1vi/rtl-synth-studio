package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlLanguage
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import com.example.domain.synth.FlowStageState
import com.example.domain.synth.FlowStageStatus
import com.example.domain.synth.PhysicalLayoutSnapshot
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.EdaSectionHeader
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.components.ExportShareHelper
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import com.example.ui.viewmodel.WorkbenchUiState
import java.util.Locale
import kotlin.math.roundToInt

private enum class LogFilter { ALL, WARN, ERR }

@Composable
fun SynthesisScreen(
  state: WorkbenchUiState,
  onSelectTopModule: (String) -> Unit,
  onSelectEngine: (SynthesisEngineProfile) -> Unit,
  onSelectLibrary: (TargetLibrary) -> Unit,
  onSelectGoal: (OptimizationGoal) -> Unit,
  onClockPeriodChange: (Double) -> Unit,
  onUpdateSdc: (String) -> Unit,
  onResetSdc: () -> Unit,
  onRunSynthesis: () -> Unit,
  onJumpToEditor: () -> Unit,
  onOpenReports: () -> Unit,
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
  onDesignNameChange: (String) -> Unit = {},
  onCoreUtilizationChange: (Int) -> Unit = {},
  onCancelFlow: () -> Unit = {},
  onResumeFlow: () -> Unit = {},
) {
  val eda = LocalEdaColors.current
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  var showAdvancedSdc by rememberSaveable { mutableStateOf(false) }
  var logFilter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
  val activeLayout = state.physicalLayout
  val savePngLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("image/png"),
  ) { uri: Uri? ->
    val png = activeLayout?.klayoutSnapshotPng
    if (uri != null && png != null && png.isNotEmpty()) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { it.write(png) }
      }.onSuccess {
        onNotify("Saved KLayout GDSII snapshot PNG (${png.size / 1024} KB).")
      }.onFailure { err ->
        onNotify("PNG save failed: ${err.localizedMessage ?: "error"}")
      }
    }
  }

  val rtlFiles = remember(state.files) {
    state.files.filter {
      it.language == RtlLanguage.VERILOG ||
        it.language == RtlLanguage.SYSTEM_VERILOG ||
        it.language == RtlLanguage.VHDL
    }
  }
  val moduleNames = state.parsedDesign?.modules?.map { it.name }.orEmpty()
  val report = (state.synthesisResult as? SynthesisResult.Success)?.report
  val synthError = state.synthesisResult as? SynthesisResult.Error

  val rawLogLines = remember(state.flowLogLines, state.synthesisResult) {
    val synthLogs = when (val res = state.synthesisResult) {
      is SynthesisResult.Success -> res.report.synthesisLog
      is SynthesisResult.Error -> res.log
      null -> emptyList()
    }
    if (state.flowLogLines.isNotEmpty()) {
      (state.flowLogLines + synthLogs).distinct()
    } else {
      synthLogs
    }
  }
  val warnings = report?.warnings.orEmpty()

  val filteredLogs = remember(rawLogLines, warnings, logFilter) {
    val combined = rawLogLines + warnings.map { "[WARN] $it" }
    when (logFilter) {
      LogFilter.ALL -> combined
      LogFilter.WARN -> combined.filter { "WARN" in it.uppercase() || "TIMING VIOLATION" in it.uppercase() }
      LogFilter.ERR -> combined.filter { "ERROR" in it.uppercase() || "FAIL" in it.uppercase() }
    }
  }

  LazyColumn(
    modifier = modifier.fillMaxSize().testTag("synthesis_lazy_list"),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Primary Run Bar & Progress Status
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Text(
                text = "RTL-to-GDSII Workspace",
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = "${rtlFiles.size} RTL source(s) · Top: ${state.parsedDesign?.topModule?.name ?: state.designName}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            EdaStatusPill(
              text = when {
                state.isSynthesizing -> "RUNNING"
                state.canResumeFlow -> "PAUSED"
                synthError != null -> "ERROR"
                warnings.isNotEmpty() -> "${warnings.size} WARN"
                else -> "READY"
              },
              color = when {
                state.isSynthesizing -> eda.warning
                state.canResumeFlow -> eda.warning
                synthError != null -> eda.error
                warnings.isNotEmpty() -> eda.warning
                else -> eda.success
              },
              containerColor = when {
                state.isSynthesizing -> eda.warningContainer
                state.canResumeFlow -> eda.warningContainer
                synthError != null -> eda.errorContainer
                warnings.isNotEmpty() -> eda.warningContainer
                else -> eda.successContainer
              },
              showDot = true,
            )
          }

          if (state.isSynthesizing) {
            LinearProgressIndicator(
              progress = { state.synthesisProgress },
              modifier = Modifier.fillMaxWidth(),
              color = MaterialTheme.colorScheme.primary,
            )
          } else {
            LinearProgressIndicator(
              progress = { state.synthesisProgress },
              modifier = Modifier.fillMaxWidth(),
              color = when {
                synthError != null -> eda.error
                state.canResumeFlow -> eda.warning
                else -> eda.success
              },
              trackColor = eda.subtleBorder.copy(alpha = 0.35f),
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = state.synthesisPhaseLabel,
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
            )
            if (report != null) {
              TextButton(
                onClick = onOpenReports,
                modifier = Modifier.testTag("synth_open_reports_button"),
              ) {
                Text("Report / GDS →", style = MaterialTheme.typography.labelLarge)
              }
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = onRunSynthesis,
              enabled = !state.isSynthesizing,
              shape = MaterialTheme.shapes.small,
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
              modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("synth_run_button"),
            ) {
              Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
              Spacer(Modifier.width(6.dp))
              Text("Run RTL-to-GDSII", style = MaterialTheme.typography.labelLarge)
            }

            if (state.isSynthesizing) {
              OutlinedButton(
                onClick = onCancelFlow,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .heightIn(min = 48.dp)
                  .testTag("synth_cancel_button"),
              ) {
                Icon(Icons.Rounded.Cancel, contentDescription = "Cancel flow", tint = eda.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cancel", style = MaterialTheme.typography.labelLarge, color = eda.error)
              }
            } else if (state.canResumeFlow) {
              OutlinedButton(
                onClick = onResumeFlow,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .heightIn(min = 48.dp)
                  .testTag("synth_resume_button"),
              ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume flow", tint = eda.cyanHighlight, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Resume", style = MaterialTheme.typography.labelLarge, color = eda.cyanHighlight)
              }
            }
          }
        }
      }
    }

    // 1b. 7-Stage Physical RTL-to-GDSII Pipeline Stepper + PDK & Device Telemetry
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("flow_stepper_card")) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          EdaSectionHeader(
            title = "Physical Flow Stepper (7 Stages)",
            subtitle = "On-device Synth → Floorplan → Place → CTS → Route → DRC → GDSII",
          )

          state.flowStages.forEach { stage ->
            FlowStageStepperRow(stage = stage)
          }

          val pdk = state.pdkPackageStatus
          val res = state.deviceResources
          if (pdk != null || res != null) {
            HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              if (pdk != null) {
                Surface(
                  color = eda.elevatedCard,
                  shape = MaterialTheme.shapes.small,
                  border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.55f)),
                  modifier = Modifier.weight(1f).testTag("pdk_checksum_card"),
                ) {
                  Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                      Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = if (pdk.isVerified) eda.success else eda.error,
                        modifier = Modifier.size(14.dp),
                      )
                      Text(
                        text = if (pdk.isVerified) "PDK Verified" else "PDK Check Failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pdk.isVerified) eda.success else eda.error,
                      )
                    }
                    Text(
                      text = "${pdk.library.shortName} · sha256:${pdk.sha256Short}",
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }
              if (res != null) {
                Surface(
                  color = eda.elevatedCard,
                  shape = MaterialTheme.shapes.small,
                  border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.55f)),
                  modifier = Modifier.weight(1f).testTag("device_telemetry_card"),
                ) {
                  Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                      Icon(
                        imageVector = Icons.Rounded.Thermostat,
                        contentDescription = null,
                        tint = if (res.isThermalThrottled || res.lowMemory) eda.warning else eda.cyanHighlight,
                        modifier = Modifier.size(14.dp),
                      )
                      Text(
                        text = "${res.thermalStatusLabel} · ${res.availableRamMb} MB free",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                    val gateGuard = when {
                      res.lowMemory -> "Low RAM guard active"
                      res.isThermalThrottled -> "Thermal throttling detected"
                      (report?.cellCount ?: 0) >= 400 -> "High gate count (${report?.cellCount} cells)"
                      else -> "Mobile silicon envelope nominal"
                    }
                    Text(
                      text = gateGuard,
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    // 1c. Verified KLayout GDSII Snapshot Preview Card
    val snapshotBitmap = activeLayout?.klayoutSnapshotBitmap
    if (activeLayout != null && snapshotBitmap != null) {
      item {
        val imageBitmap = remember(activeLayout.gdsSha256, snapshotBitmap) {
          snapshotBitmap.asImageBitmap()
        }
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("klayout_snapshot_card")) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  text = "KLayout GDSII Snapshot",
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = "${activeLayout.moduleName}.gds · ${activeLayout.gdsStructureCount} cells · ${activeLayout.gdsSrefCount} SREF · ${activeLayout.gdsBytes.size}B",
                  style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              EdaStatusPill(
                text = if (activeLayout.gdsVerified) "GDS2 v600 OK" else "GDS WARN",
                color = if (activeLayout.gdsVerified) eda.success else eda.warning,
                containerColor = if (activeLayout.gdsVerified) eda.successContainer else eda.warningContainer,
                showDot = true,
              )
            }

            Box(
              modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1280f / 860f)
                .clip(MaterialTheme.shapes.medium)
                .background(Color(0xFF11151E))
                .border(1.dp, eda.subtleBorder.copy(alpha = 0.7f), MaterialTheme.shapes.medium),
            ) {
              Image(
                bitmap = imageBitmap,
                contentDescription = "KLayout GDSII physical layout snapshot for ${activeLayout.moduleName}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().testTag("klayout_snapshot_image"),
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedButton(
                onClick = onOpenReports,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp).testTag("klayout_open_viewer_button"),
              ) {
                Icon(Icons.Rounded.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Inspect Layers", style = MaterialTheme.typography.labelLarge)
              }

              if (activeLayout.klayoutSnapshotPng.isNotEmpty()) {
                OutlinedButton(
                  onClick = { savePngLauncher.launch("${activeLayout.moduleName}_klayout.png") },
                  shape = MaterialTheme.shapes.small,
                  modifier = Modifier.heightIn(min = 48.dp).testTag("klayout_save_png_button"),
                ) {
                  Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("Save PNG", style = MaterialTheme.typography.labelLarge)
                }
              }

              if (report != null) {
                Button(
                  onClick = { ExportShareHelper.shareBuildBundle(context, report, activeLayout, onNotify) },
                  shape = MaterialTheme.shapes.small,
                  modifier = Modifier.heightIn(min = 48.dp).testTag("klayout_share_bundle_button"),
                ) {
                  Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("Share .gds + .lyp", style = MaterialTheme.typography.labelLarge)
                }
              }
            }
          }
        }
      }
    }

    // 2. Source Selection & Engine Configuration
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          EdaSectionHeader(
            title = "RTL Sources & Engine",
            subtitle = "Design name, top module, mapper profile, and verified PDK",
          )

          OutlinedTextField(
            value = state.designName,
            onValueChange = onDesignNameChange,
            label = { Text("Design Name") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
            modifier = Modifier.fillMaxWidth().testTag("synth_design_name_input"),
          )

          if (rtlFiles.isNotEmpty()) {
            Row(
              modifier = Modifier.horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              rtlFiles.forEach { file ->
                EdaStatusPill(
                  text = file.name,
                  color = eda.cyanHighlight,
                  containerColor = eda.cyanContainer,
                )
              }
            }
          }

          if (moduleNames.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Top Module", style = MaterialTheme.typography.labelLarge)
              Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                val activeTop = state.parsedDesign?.topModule?.name
                moduleNames.forEach { mod ->
                  FilterChip(
                    selected = mod == activeTop,
                    onClick = { onSelectTopModule(mod) },
                    label = { Text(mod, style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily)) },
                  )
                }
              }
            }
          }

          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Synthesis Engine", style = MaterialTheme.typography.labelLarge)
            Row(
              modifier = Modifier.horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              SynthesisEngineProfile.entries.forEach { eng ->
                FilterChip(
                  selected = state.synthesisEngine == eng,
                  onClick = { onSelectEngine(eng) },
                  label = { Text(eng.shortName, style = MaterialTheme.typography.labelMedium) },
                  modifier = Modifier.testTag("engine_chip_${eng.id}"),
                )
              }
            }
            Text(
              text = state.synthesisEngine.summary,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.6f))

          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Target Technology Library", style = MaterialTheme.typography.labelLarge)
            Row(
              modifier = Modifier.horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              TargetLibrary.entries.forEach { lib ->
                FilterChip(
                  selected = state.targetLibrary == lib,
                  onClick = { onSelectLibrary(lib) },
                  label = { Text(lib.shortName, style = MaterialTheme.typography.labelMedium) },
                  modifier = Modifier.testTag("library_chip_${lib.id}"),
                )
              }
            }
            Text(
              text = "${state.targetLibrary.displayName} · ${state.targetLibrary.processNode} @ ${state.targetLibrary.nominalVoltage}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    // 3. Timing & Optimization Constraints (Progressive Disclosure)
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          val freqMhz = 1000.0 / state.clockPeriodNs.coerceAtLeast(0.4)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Target Clock Period", style = MaterialTheme.typography.labelLarge)
            Text(
              text = "${"%.2f".format(Locale.US, state.clockPeriodNs)} ns (${"%.0f".format(Locale.US, freqMhz)} MHz)",
              style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
              color = eda.cyanHighlight,
            )
          }
          Slider(
            value = state.clockPeriodNs.toFloat(),
            onValueChange = { onClockPeriodChange(it.toDouble()) },
            valueRange = 0.5f..20f,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Core Utilization Target", style = MaterialTheme.typography.labelLarge)
            Text(
              text = "${state.coreUtilizationPercent}% density",
              style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
              color = MaterialTheme.colorScheme.primary,
            )
          }
          Slider(
            value = state.coreUtilizationPercent.toFloat(),
            onValueChange = { onCoreUtilizationChange(it.roundToInt().coerceIn(30, 85)) },
            valueRange = 30f..85f,
            modifier = Modifier.testTag("utilization_slider"),
          )

          Text("Optimization Goal", style = MaterialTheme.typography.labelLarge)
          Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OptimizationGoal.entries.forEach { g ->
              FilterChip(
                selected = state.optimizationGoal == g,
                onClick = { onSelectGoal(g) },
                label = { Text(g.displayName, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.testTag("goal_chip_${g.id}"),
              )
            }
          }

          Surface(
            onClick = { showAdvancedSdc = !showAdvancedSdc },
            color = eda.elevatedCard,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text("Advanced SDC Timing Constraints", style = MaterialTheme.typography.labelLarge)
              }
              Icon(
                imageVector = if (showAdvancedSdc) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
              )
            }
          }

          if (showAdvancedSdc) {
            var localSdc by remember(state.sdcText) { mutableStateOf(state.sdcText) }
            OutlinedTextField(
              value = localSdc,
              onValueChange = { localSdc = it },
              label = { Text("SDC Constraints") },
              textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
              minLines = 4,
              maxLines = 8,
              modifier = Modifier.fillMaxWidth().testTag("sdc_constraints_editor"),
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.End,
            ) {
              TextButton(onClick = onResetSdc) { Text("Reset Default") }
              Spacer(Modifier.width(8.dp))
              Button(
                onClick = { onUpdateSdc(localSdc) },
                modifier = Modifier.testTag("apply_sdc_button"),
              ) {
                Text("Apply SDC")
              }
            }
          }
        }
      }
    }

    // 4. Error & Warning Diagnostics (if any)
    if (synthError != null) {
      item {
        Surface(
          color = eda.errorContainer,
          shape = MaterialTheme.shapes.medium,
          border = BorderStroke(1.dp, eda.error.copy(alpha = 0.7f)),
          modifier = Modifier.fillMaxWidth().testTag("synthesis_error_card"),
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = eda.error)
              Text(
                text = "Synthesis Failed · Line ${synthError.line}",
                style = MaterialTheme.typography.titleSmall,
                color = eda.error,
              )
            }
            Text(synthError.message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onJumpToEditor) {
              Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Open Editor at Line ${synthError.line}")
            }
          }
        }
      }
    }

    if (warnings.isNotEmpty()) {
      item {
        Surface(
          color = eda.warningContainer,
          shape = MaterialTheme.shapes.medium,
          border = BorderStroke(1.dp, eda.warning.copy(alpha = 0.65f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = eda.warning, modifier = Modifier.size(18.dp))
              Text(
                text = "Warnings (${warnings.size})",
                style = MaterialTheme.typography.titleSmall,
                color = eda.warning,
              )
            }
            warnings.forEach { w ->
              Text(
                text = "• $w",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      }
    }

    // 5. Execution Log Console (JetBrains Mono)
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Execution Log", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              LogFilter.entries.forEach { f ->
                FilterChip(
                  selected = logFilter == f,
                  onClick = { logFilter = f },
                  label = { Text(f.name, style = MaterialTheme.typography.labelSmall) },
                )
              }
              IconButton(
                onClick = {
                  clipboard.setText(AnnotatedString(filteredLogs.joinToString("\n")))
                  onNotify("Synthesis log copied to clipboard.")
                },
                modifier = Modifier.size(36.dp),
              ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy log", modifier = Modifier.size(16.dp))
              }
            }
          }

          Surface(
            color = eda.codeSurface,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              if (filteredLogs.isEmpty()) {
                Text(
                  text = "No log entries matching filter.",
                  style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              } else {
                filteredLogs.forEach { line ->
                  val color = when {
                    "[ERROR]" in line || "FAIL" in line -> eda.error
                    "[WARN]" in line -> eda.warning
                    "finished successfully" in line || "PASS" in line || "COMPLETE" in line.uppercase() -> eda.success
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                  }
                  Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall.copy(
                      fontFamily = JetBrainsMonoFamily,
                      fontWeight = if ("[ERROR]" in line || "[WARN]" in line) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = color,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun FlowStageStepperRow(stage: FlowStageState) {
  val eda = LocalEdaColors.current
  val accent = when (stage.status) {
    FlowStageStatus.PASSED -> eda.success
    FlowStageStatus.RUNNING -> MaterialTheme.colorScheme.primary
    FlowStageStatus.FAILED -> eda.error
    FlowStageStatus.CANCELED -> eda.warning
    FlowStageStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  val icon = when (stage.status) {
    FlowStageStatus.PASSED -> Icons.Rounded.CheckCircle
    FlowStageStatus.RUNNING -> Icons.Rounded.Memory
    FlowStageStatus.FAILED -> Icons.Rounded.ErrorOutline
    FlowStageStatus.CANCELED -> Icons.Rounded.PauseCircleOutline
    FlowStageStatus.PENDING -> Icons.Rounded.HourglassEmpty
  }

  Surface(
    color = eda.elevatedCard,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(
      width = 1.dp,
      color = if (stage.status == FlowStageStatus.RUNNING) MaterialTheme.colorScheme.primary else eda.subtleBorder.copy(alpha = 0.55f),
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("flow_stage_${stage.id.name}"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = "${stage.id.title} ${stage.status.name}",
        tint = accent,
        modifier = Modifier.size(18.dp),
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${stage.id.order}. ${stage.id.title}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = if (stage.durationMs > 0L) "${stage.durationMs} ms" else stage.id.toolLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text = stage.headlineMetric,
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = accent,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
