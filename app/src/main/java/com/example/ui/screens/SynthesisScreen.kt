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
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
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
import com.example.domain.synth.DftConfig
import com.example.domain.synth.DftScanStyle
import com.example.domain.synth.FlowStageState
import com.example.domain.synth.FlowStageStatus
import com.example.domain.synth.LecOverallStatus
import com.example.domain.synth.PdkCatalogManager
import com.example.domain.synth.PhysicalLayoutSnapshot
import com.example.domain.synth.SdcParser
import com.example.domain.synth.StaticTimingAnalyzer
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.EdaCompactMetric
import com.example.ui.components.EdaPanelHeader
import com.example.ui.components.EdaSectionHeader
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.components.ExportShareHelper
import com.example.ui.components.RtlDrcPreSynthCard
import com.example.ui.components.TimingViolationCalloutStrip
import com.example.ui.components.VisualSdcEditorCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import com.example.ui.viewmodel.WorkbenchUiState
import java.io.InputStream
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
  bitBlastGates: Boolean = false,
  onToggleBitBlastGates: () -> Unit = {},
  onStartPdkDownload: (String, String?) -> Unit = { _, _ -> },
  onPausePdkDownload: () -> Unit = {},
  onCancelPdkDownload: () -> Unit = {},
  onImportLocalPdk: (String, InputStream) -> Unit = { _, _ -> },
  localBridgeEnabled: Boolean = false,
  localBridgeEndpoint: String = "http://127.0.0.1:8765",
  onTestRemoteWorker: () -> Unit = {},
  onRunRemoteWorker: () -> Unit = {},
  onToggleDftScan: () -> Unit = {},
  onUpdateDftConfig: ((DftConfig) -> DftConfig) -> Unit = {},
  onOpenSchematic: () -> Unit = {},
  onOpenAnalysis: () -> Unit = {},
  onOpenFormalLec: () -> Unit = onOpenAnalysis,
  onRunRtlDrc: () -> Unit = {},
  onJumpToDrcLine: (Int) -> Unit = { onJumpToEditor() },
) {
  val eda = LocalEdaColors.current
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  var showAdvancedSdc by rememberSaveable { mutableStateOf(false) }
  var showPdkManager by rememberSaveable { mutableStateOf(false) }
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
  val importPdkLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri: Uri? ->
    if (uri != null) {
      val name = uri.lastPathSegment?.substringAfterLast('/') ?: "custom_pdk.lef"
      runCatching {
        val stream = context.contentResolver.openInputStream(uri)
          ?: throw IllegalStateException("Cannot open selected .lef/.lib file.")
        onImportLocalPdk(name, stream)
      }.onFailure { err ->
        onNotify("PDK import failed: ${err.localizedMessage ?: "error"}")
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
    // =========================================================================
    // SECTION 1: EXECUTION & RUN CONTROL
    // =========================================================================
    item {
      EdaSectionHeader(
        title = "1. Execution & Flow Control",
        subtitle = "Run RTL-to-GDSII compilation, pause/resume stages, and inspect telemetry",
      )
    }

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
                  onClick = { showPdkManager = !showPdkManager },
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
                        text = if (pdk.customCellCount > 0) "${pdk.customCellCount} PDK Cells" else if (pdk.isVerified) "PDK Verified" else "PDK Check",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pdk.isVerified) eda.success else eda.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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

    // 1c. Pre-Synthesis RTL Design Rule Check (DRC)
    item {
      RtlDrcPreSynthCard(
        drcReport = state.rtlDrcReport,
        hasParseError = state.parseError != null,
        onRunDrcScan = onRunRtlDrc,
        onJumpToLine = onJumpToDrcLine,
      )
    }

    // =========================================================================
    // SECTION 2: CONFIGURATION (RTL, PDK, TIMING, DFT)
    // =========================================================================
    item {
      EdaSectionHeader(
        title = "2. Configuration",
        subtitle = "Target top module, PDK cell library, SDC constraints & DFT scan setup",
      )
    }

    // 1b-2. Real PDK Downloader & Local .LEF/.LIB Importer Card
    item {
      val pdk = state.pdkPackageStatus
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Surface(
            onClick = { showPdkManager = !showPdkManager },
            color = eda.elevatedCard,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth().testTag("pdk_manager_toggle"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = eda.cyanHighlight, modifier = Modifier.size(16.dp))
                Text("Real PDK Downloader & .LEF / .LIB Importer", style = MaterialTheme.typography.labelLarge)
              }
              Icon(
                imageVector = if (showPdkManager) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
              )
            }
          }

          if (showPdkManager) {
            var pdkUrl by remember(state.targetLibrary) {
              mutableStateOf(PdkCatalogManager.defaultSourceUrl(state.targetLibrary))
            }
            var expectedSha by remember(state.targetLibrary) { mutableStateOf("") }
            val dl = state.pdkDownloadProgress
            Surface(
              color = eda.codeSurface,
              shape = MaterialTheme.shapes.small,
              border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.65f)),
              modifier = Modifier.fillMaxWidth().testTag("pdk_manager_panel"),
            ) {
              Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                if (pdk != null) {
                  Text(
                    text = pdk.ruleSummary,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = eda.cyanHighlight,
                  )
                }
                OutlinedTextField(
                  value = pdkUrl,
                  onValueChange = { pdkUrl = it },
                  label = { Text("PDK .lef / .lib Mirror URL") },
                  singleLine = true,
                  textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  modifier = Modifier.fillMaxWidth().testTag("pdk_url_input"),
                )
                OutlinedTextField(
                  value = expectedSha,
                  onValueChange = { expectedSha = it },
                  label = { Text("Optional Expected SHA-256 (64 hex chars)") },
                  singleLine = true,
                  textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  modifier = Modifier.fillMaxWidth().testTag("pdk_sha_input"),
                )
                if (dl != null) {
                  if (dl.isDownloading) {
                    if (dl.totalBytes > 0L) {
                      LinearProgressIndicator(
                        progress = { dl.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = eda.cyanHighlight,
                      )
                    } else {
                      LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = eda.cyanHighlight,
                      )
                    }
                  }
                  Text(
                    text = dl.statusMessage,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = if (dl.error != null) eda.error else eda.cyanHighlight,
                  )
                }
                Row(
                  modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  if (dl?.isDownloading == true) {
                    OutlinedButton(
                      onClick = onPausePdkDownload,
                      modifier = Modifier.heightIn(min = 48.dp).testTag("pdk_pause_button"),
                    ) {
                      Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                      Spacer(Modifier.width(4.dp))
                      Text("Pause", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                      onClick = onCancelPdkDownload,
                      modifier = Modifier.heightIn(min = 48.dp).testTag("pdk_cancel_button"),
                    ) {
                      Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                      Spacer(Modifier.width(4.dp))
                      Text("Cancel", style = MaterialTheme.typography.labelLarge, color = eda.error)
                    }
                  } else {
                    Button(
                      onClick = { onStartPdkDownload(pdkUrl, expectedSha.ifBlank { null }) },
                      modifier = Modifier.heightIn(min = 48.dp).testTag("pdk_download_button"),
                    ) {
                      Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                      Spacer(Modifier.width(6.dp))
                      Text(if (dl?.isPaused == true) "Resume Download" else "Fetch & Verify PDK", style = MaterialTheme.typography.labelLarge)
                    }
                  }
                  OutlinedButton(
                    onClick = { importPdkLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("pdk_import_local_button"),
                  ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import .lef / .lib", style = MaterialTheme.typography.labelLarge)
                  }
                }
              }
            }
          }
        }
      }
    }

    // 1b-3. Optional Remote OpenROAD / OpenLane Worker Card
    if (localBridgeEnabled) {
      item {
        Surface(
          color = eda.elevatedCard,
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth().testTag("remote_worker_card"),
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
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text("Remote OpenROAD / OpenLane Worker", style = MaterialTheme.typography.labelLarge)
              }
              Text(
                text = localBridgeEndpoint,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            if (!state.remoteWorkerStatus.isNullOrBlank()) {
              Text(
                text = state.remoteWorkerStatus,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = eda.cyanHighlight,
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(
                onClick = onTestRemoteWorker,
                enabled = !state.isTestingRemoteWorker,
                modifier = Modifier.heightIn(min = 48.dp).testTag("remote_worker_ping_button"),
              ) {
                Text("Ping Health", style = MaterialTheme.typography.labelLarge)
              }
              Button(
                onClick = onRunRemoteWorker,
                enabled = !state.isTestingRemoteWorker && !state.isSynthesizing,
                modifier = Modifier.heightIn(min = 48.dp).testTag("remote_worker_run_button"),
              ) {
                Text("Run Remote Signoff", style = MaterialTheme.typography.labelLarge)
              }
            }
          }
        }
      }
    }

    // =========================================================================
    // SECTION 3: RESULTS & DIAGNOSTICS
    // =========================================================================
    item {
      EdaSectionHeader(
        title = "3. Results & Diagnostics",
        subtitle = "Synthesis status summary, KLayout GDSII snapshot, warnings & logs",
      )
    }

    if (report != null) {
      item {
        val timingMet = report.wnsNs >= 0.0
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("synth_results_summary_card")) {
          Column {
            EdaPanelHeader(
              title = "Synthesis Result · ${report.moduleName}",
              subtitle = "${report.targetLibrary.shortName} · ${report.engineProfile.shortName} · ${report.synthesisDurationMs} ms",
              icon = Icons.Rounded.CheckCircle,
              trailing = {
                EdaStatusPill(
                  text = if (timingMet) "MET" else "VIOL",
                  color = if (timingMet) eda.success else eda.error,
                  containerColor = if (timingMet) eda.successContainer else eda.errorContainer,
                )
              },
            )
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EdaCompactMetric("Cells", report.cellCount.toString(), modifier = Modifier.weight(1f))
                EdaCompactMetric(
                  label = "WNS",
                  value = "${if (report.wnsNs >= 0) "+" else ""}${"%.2f".format(Locale.US, report.wnsNs)} ns",
                  valueColor = if (timingMet) eda.success else eda.error,
                  modifier = Modifier.weight(1f),
                )
                EdaCompactMetric(
                  label = "Fmax",
                  value = "${"%.1f".format(Locale.US, report.fmaxMhz)} MHz",
                  valueColor = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.weight(1f),
                )
              }
              if (!timingMet) {
                val remediationPlan = remember(report, activeLayout) {
                  val staSummary = StaticTimingAnalyzer.analyze(report, activeLayout)
                  StaticTimingAnalyzer.buildViolationRemediation(report, staSummary)
                }
                if (remediationPlan != null) {
                  TimingViolationCalloutStrip(
                    plan = remediationPlan,
                    onApplyClockPeriod = onClockPeriodChange,
                    onApplyOptimizationGoal = onSelectGoal,
                    onOpenTimingGuide = onOpenAnalysis,
                  )
                }
              }
              val lec = state.lecReport
              if (lec != null) {
                val (lecColor, lecBg) = when (lec.overallStatus) {
                  LecOverallStatus.EQUIVALENT -> eda.success to eda.successContainer
                  LecOverallStatus.NON_EQUIVALENT -> eda.error to eda.errorContainer
                  LecOverallStatus.INCONCLUSIVE -> eda.warning to eda.warningContainer
                  LecOverallStatus.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant to eda.elevatedCard
                }
                Surface(
                  color = eda.elevatedCard,
                  shape = MaterialTheme.shapes.small,
                  border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.55f)),
                  modifier = Modifier.fillMaxWidth().testTag("synth_lec_summary_strip"),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Row(
                      modifier = Modifier.weight(1f).padding(end = 8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                      Icon(Icons.AutoMirrored.Rounded.FactCheck, contentDescription = null, tint = lecColor, modifier = Modifier.size(16.dp))
                      Column {
                        Text("Formal Equivalence (LEC)", style = MaterialTheme.typography.labelMedium)
                        Text(
                          text = "${lec.matchedCount}/${lec.totalComparePoints} mapped · ${lec.provenCount + lec.boundedEquivalentCount} equiv · ${lec.nonEquivalentCount} fail",
                          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                        )
                      }
                    }
                    EdaStatusPill(
                      text = lec.overallStatus.badge,
                      color = lecColor,
                      containerColor = lecBg,
                      showDot = true,
                    )
                  }
                }
              }
              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                OutlinedButton(
                  onClick = onOpenSchematic,
                  modifier = Modifier.heightIn(min = 48.dp).testTag("synth_open_schematic_button"),
                ) {
                  Icon(Icons.Rounded.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("View Schematic", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                  onClick = onOpenAnalysis,
                  modifier = Modifier.heightIn(min = 48.dp).testTag("synth_open_analysis_button"),
                ) {
                  Icon(Icons.Rounded.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("STA / Area / Power", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                  onClick = onOpenFormalLec,
                  modifier = Modifier.heightIn(min = 48.dp).testTag("synth_open_lec_button"),
                ) {
                  Icon(Icons.AutoMirrored.Rounded.FactCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("Formal LEC", style = MaterialTheme.typography.labelMedium)
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

          Text("Optimization Goal & Gate Mapping", style = MaterialTheme.typography.labelLarge)
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
            FilterChip(
              selected = bitBlastGates,
              onClick = onToggleBitBlastGates,
              label = {
                Text(
                  if (bitBlastGates) "Bit-Blasted 1-Bit Gates ✓" else "Macro Cell View",
                  style = MaterialTheme.typography.labelMedium,
                )
              },
              modifier = Modifier.testTag("bit_blast_chip"),
            )
          }
        }
      }
    }

    // 3a-bis. Visual & Raw SDC Timing Constraints Card
    item {
      val sdcSummary = remember(state.sdcText, state.clockPeriodNs) {
        SdcParser.parse(state.sdcText, state.clockPeriodNs).summaryLine()
      }
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("sdc_constraints_card")) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            onClick = { showAdvancedSdc = !showAdvancedSdc },
            color = eda.elevatedCard,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth().testTag("advanced_sdc_toggle"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Column {
                  Text("Advanced SDC Timing Constraints", style = MaterialTheme.typography.labelLarge)
                  Text(
                    text = sdcSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
              Icon(
                imageVector = if (showAdvancedSdc) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (showAdvancedSdc) "Collapse SDC Editor" else "Expand SDC Editor",
              )
            }
          }

          if (showAdvancedSdc) {
            VisualSdcEditorCard(
              sdcText = state.sdcText,
              clockPeriodNs = state.clockPeriodNs,
              ports = state.parsedDesign?.topModule?.ports.orEmpty(),
              targetLibrary = state.targetLibrary,
              moduleName = state.parsedDesign?.topModule?.name ?: state.designName,
              onUpdateSdc = onUpdateSdc,
              onResetSdc = onResetSdc,
            )
          }
        }
      }
    }

    // 3b. Design-for-Testability (DFT) & Scan Chain Configuration
    item {
      val dft = state.dftConfig
      val dftRep = report?.dftReport
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("dft_config_card")) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            EdaSectionHeader(
              title = "DFT & Scan Insertion",
              subtitle = "Scan chains, lockup latches, JTAG TAP & ATPG",
              modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            EdaStatusPill(
              text = if (dft.scanInsertionEnabled) "SCAN ON" else "AUDIT ONLY",
              color = if (dft.scanInsertionEnabled) eda.success else eda.cyanHighlight,
              containerColor = if (dft.scanInsertionEnabled) eda.successContainer else eda.cyanContainer,
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text("Insert Scan Chains (SDFF)", style = MaterialTheme.typography.labelLarge)
              Text(
                text = if (dft.scanInsertionEnabled) {
                  "Converts sequential FFs to ${dft.scanStyle.shortName} scan cells with scan_en/scan_in/scan_out"
                } else {
                  "Runs passive DFT DRC & stuck-at fault audit without modifying mapped FFs"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = dft.scanInsertionEnabled,
              onCheckedChange = { onToggleDftScan() },
              modifier = Modifier.testTag("dft_scan_enable_switch"),
            )
          }

          if (dft.scanInsertionEnabled) {
            HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.45f))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = "Scan Architecture Style",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                DftScanStyle.entries.forEach { style ->
                  FilterChip(
                    selected = dft.scanStyle == style,
                    onClick = { onUpdateDftConfig { it.copy(scanStyle = style) } },
                    label = { Text(style.displayName, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.testTag("dft_style_${style.id}"),
                  )
                }
              }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = "Target Scan Chains",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                listOf(1, 2, 4).forEach { count ->
                  FilterChip(
                    selected = dft.targetChainCount == count,
                    onClick = { onUpdateDftConfig { it.copy(targetChainCount = count) } },
                    label = {
                      Text(
                        text = if (count == 1) "1 Chain" else "$count Chains",
                        style = MaterialTheme.typography.labelMedium,
                      )
                    },
                    modifier = Modifier.testTag("dft_chains_$count"),
                  )
                }
              }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = "Signoff Test Structures",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                FilterChip(
                  selected = dft.insertLockupLatches,
                  onClick = { onUpdateDftConfig { it.copy(insertLockupLatches = !it.insertLockupLatches) } },
                  label = { Text("Lockup Latches", style = MaterialTheme.typography.labelMedium) },
                  modifier = Modifier.testTag("dft_lockup_chip"),
                )
                FilterChip(
                  selected = dft.insertJtagBoundaryTap,
                  onClick = { onUpdateDftConfig { it.copy(insertJtagBoundaryTap = !it.insertJtagBoundaryTap) } },
                  label = { Text("IEEE 1149.1 JTAG TAP", style = MaterialTheme.typography.labelMedium) },
                  modifier = Modifier.testTag("dft_jtag_chip"),
                )
                FilterChip(
                  selected = dft.insertMbistCollar,
                  onClick = { onUpdateDftConfig { it.copy(insertMbistCollar = !it.insertMbistCollar) } },
                  label = { Text("MBIST Collar", style = MaterialTheme.typography.labelMedium) },
                  modifier = Modifier.testTag("dft_mbist_chip"),
                )
              }
            }
          }

          if (dftRep != null) {
            Surface(
              color = eda.codeSurface.copy(alpha = 0.7f),
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(
                    text = "Stuck-At Cov: ${"%.1f".format(Locale.US, dftRep.stuckAtCoveragePct)}% · ${dftRep.detectedFaults}/${dftRep.totalFaults} faults",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                    color = eda.cyanHighlight,
                  )
                  Text(
                    text = "${dftRep.scanChains.size} scan chain(s) · ${dftRep.atpgVectors.size} ATPG vectors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                TextButton(
                  onClick = onOpenReports,
                  modifier = Modifier.heightIn(min = 48.dp).testTag("dft_view_report_button"),
                ) {
                  Text("View DFT")
                }
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
          onClick = onJumpToEditor,
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
            TextButton(onClick = onJumpToEditor, modifier = Modifier.testTag("jump_to_error_line_button")) {
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
