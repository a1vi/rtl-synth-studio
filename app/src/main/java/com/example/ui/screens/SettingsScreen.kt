package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AppSettings
import com.example.data.model.ExportFormat
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

@Composable
fun SettingsScreen(
  settings: AppSettings,
  savedProjectCount: Int,
  savedRunCount: Int,
  storageKb: Double,
  onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
  onClearSynthesisCache: () -> Unit,
  onResetWorkspace: () -> Unit,
  modifier: Modifier = Modifier,
  remoteWorkerStatus: String? = null,
  isTestingRemoteWorker: Boolean = false,
  onTestRemoteWorker: () -> Unit = {},
) {
  val eda = LocalEdaColors.current

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Appearance Preferences
    item {
      SettingsGroupCard(
        icon = Icons.Rounded.Palette,
        title = "Appearance",
        subtitle = "Industrial IDE theme and interface density",
      ) {
        SettingSwitchRow(
          title = "Dark Industrial Theme",
          subtitle = "Dark charcoal (#121417) EDA workspace with electric blue (#3987F5) accents",
          checked = settings.darkTheme,
          onCheckedChange = { enabled -> onUpdateSettings { it.copy(darkTheme = enabled) } },
          testTag = "setting_dark_theme_switch",
        )
        HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
        SettingSwitchRow(
          title = "Compact IDE Density",
          subtitle = "Tighter vertical padding for high-density signal tables",
          checked = settings.compactDensity,
          onCheckedChange = { enabled -> onUpdateSettings { it.copy(compactDensity = enabled) } },
        )
      }
    }

    // 2. Editor Configuration
    item {
      SettingsGroupCard(
        icon = Icons.Rounded.Code,
        title = "HDL Editor Configuration",
        subtitle = "JetBrains Mono workspace behavior & auto-save",
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("Editor Font Size", style = MaterialTheme.typography.bodyMedium)
            Text(
              text = "${settings.editorFontSizeSp} sp",
              style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
              color = MaterialTheme.colorScheme.primary,
            )
          }
          Slider(
            value = settings.editorFontSizeSp.toFloat(),
            onValueChange = { v -> onUpdateSettings { it.copy(editorFontSizeSp = v.toInt().coerceIn(11, 18)) } },
            valueRange = 11f..18f,
            steps = 6,
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Indent Width", style = MaterialTheme.typography.bodyMedium)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 4).forEach { w ->
              FilterChip(
                selected = settings.tabWidth == w,
                onClick = { onUpdateSettings { it.copy(tabWidth = w) } },
                label = { Text("$w spaces", style = MaterialTheme.typography.labelMedium) },
              )
            }
          }
        }

        HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
        SettingSwitchRow(
          title = "Auto-Indentation",
          subtitle = "Preserve leading indent & indent after begin / module / entity",
          checked = settings.autoIndent,
          onCheckedChange = { enabled -> onUpdateSettings { it.copy(autoIndent = enabled) } },
        )
        SettingSwitchRow(
          title = "Show Line Numbers & Diagnostics Gutter",
          subtitle = "Render JetBrains Mono line indices and error markers",
          checked = settings.showLineNumbers,
          onCheckedChange = { enabled -> onUpdateSettings { it.copy(showLineNumbers = enabled) } },
        )
        SettingSwitchRow(
          title = "Soft Word Wrap",
          subtitle = "Wrap long RTL expressions inside viewport width",
          checked = settings.wordWrap,
          onCheckedChange = { enabled -> onUpdateSettings { it.copy(wordWrap = enabled) } },
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Local Auto-Save Interval", style = MaterialTheme.typography.bodyMedium)
          Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            listOf(5 to "5s", 15 to "15s", 30 to "30s", 0 to "Manual").forEach { (sec, label) ->
              FilterChip(
                selected = settings.autoSaveIntervalSec == sec,
                onClick = { onUpdateSettings { it.copy(autoSaveIntervalSec = sec) } },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
              )
            }
          }
        }
      }
    }

    // 3. Synthesis Engine Settings
    item {
      SettingsGroupCard(
        icon = Icons.Rounded.Memory,
        title = "Synthesis Engine",
        subtitle = "Default elaboration & technology mapping pipeline",
      ) {
        Row(
          modifier = Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          SynthesisEngineProfile.entries.forEach { eng ->
            FilterChip(
              selected = settings.defaultEngine == eng,
              onClick = { onUpdateSettings { it.copy(defaultEngine = eng) } },
              label = { Text(eng.shortName, style = MaterialTheme.typography.labelMedium) },
            )
          }
        }
        Text(
          text = settings.defaultEngine.summary,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
        SettingSwitchRow(
          title = "Aggressive Logic Restructuring",
          subtitle = "Enable deeper Boolean factoring and mux decomposition",
          checked = settings.aggressiveOptimization,
          onCheckedChange = { enabled -> onUpdateSettings { it.copy(aggressiveOptimization = enabled) } },
        )
        SettingSwitchRow(
          title = "Retain Module Hierarchy",
          subtitle = "Preserve submodule boundary names during netlist emission",
          checked = settings.retainHierarchy,
          onCheckedChange = { enabled -> onUpdateSettings { it.copy(retainHierarchy = enabled) } },
        )
        SettingSwitchRow(
          title = "Bit-Blasted 1-Bit Gate View",
          subtitle = "Decompose arithmetic (+, *, -) and wide muxes into 1-bit XOR2/AND2/OR2 chains",
          checked = settings.bitBlastGates,
          onCheckedChange = { enabled -> onUpdateSettings { it.copy(bitBlastGates = enabled) } },
          testTag = "setting_bit_blast_switch",
        )
      }
    }

    // 4. Technology Library Management
    item {
      SettingsGroupCard(
        icon = Icons.Rounded.Layers,
        title = "Technology Library Management",
        subtitle = "Standard cell PDKs and FPGA fabrics bundled on device",
      ) {
        TargetLibrary.entries.forEachIndexed { idx, lib ->
          val isDefault = settings.defaultLibrary == lib
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(lib.displayName, style = MaterialTheme.typography.titleSmall)
                EdaStatusPill(
                  text = "${lib.processNode} · ${lib.nominalVoltage}",
                  color = eda.cyanHighlight,
                  containerColor = eda.cyanContainer,
                )
              }
              Text(
                text = lib.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            FilterChip(
              selected = isDefault,
              onClick = { onUpdateSettings { it.copy(defaultLibrary = lib) } },
              label = { Text(if (isDefault) "Default" else "Set Default", style = MaterialTheme.typography.labelSmall) },
            )
          }
          if (idx < TargetLibrary.entries.lastIndex) {
            HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.45f))
          }
        }
      }
    }

    // 5. Backend / Local Engine Connection
    item {
      SettingsGroupCard(
        icon = Icons.Rounded.Dns,
        title = "Engine Backend Connection",
        subtitle = "100% deterministic local execution — zero cloud dependency",
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("On-Device Native EDA Core", style = MaterialTheme.typography.titleSmall)
            Text(
              text = "Verilog / SystemVerilog / VHDL parser, STA & simulator active locally",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          EdaStatusPill(
            text = "ACTIVE",
            color = eda.success,
            containerColor = eda.successContainer,
            showDot = true,
          )
        }
        HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
        SettingSwitchRow(
          title = "Local LAN Workstation Bridge (Optional)",
          subtitle = "Configure a local-network Yosys/OpenROAD bridge URI",
          checked = settings.localBridgeEnabled,
          onCheckedChange = { enabled -> onUpdateSettings { it.copy(localBridgeEnabled = enabled) } },
        )
        if (settings.localBridgeEnabled) {
          OutlinedTextField(
            value = settings.localBridgeEndpoint,
            onValueChange = { url -> onUpdateSettings { it.copy(localBridgeEndpoint = url) } },
            label = { Text("Remote OpenROAD / Yosys Worker URI") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
            modifier = Modifier.fillMaxWidth().testTag("remote_worker_uri_input"),
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = remoteWorkerStatus ?: "GET /health · POST /api/v1/flow",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(
              onClick = onTestRemoteWorker,
              enabled = !isTestingRemoteWorker,
              modifier = Modifier.heightIn(min = 48.dp).testTag("settings_test_remote_worker_button"),
            ) {
              Text(if (isTestingRemoteWorker) "Pinging..." else "Test Worker", style = MaterialTheme.typography.labelMedium)
            }
          }
        }
      }
    }

    // 6. Storage Management
    item {
      SettingsGroupCard(
        icon = Icons.Rounded.Storage,
        title = "Storage Management",
        subtitle = "App-private workspace files on device",
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "$savedProjectCount projects · $savedRunCount cached reports",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "${"%.1f".format(Locale.US, storageKb)} KB used",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
            color = eda.cyanHighlight,
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(
            onClick = onClearSynthesisCache,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
          ) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Clear History", style = MaterialTheme.typography.labelLarge, maxLines = 1)
          }
          OutlinedButton(
            onClick = onResetWorkspace,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
          ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Reset Draft", style = MaterialTheme.typography.labelLarge, maxLines = 1)
          }
        }
      }
    }

    // 7. Export Preferences
    item {
      SettingsGroupCard(
        icon = Icons.Rounded.FileDownload,
        title = "Export Preferences",
        subtitle = "Default format for netlist and report exports",
      ) {
        Row(
          modifier = Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          ExportFormat.entries.forEach { fmt ->
            FilterChip(
              selected = settings.defaultExportFormat == fmt,
              onClick = { onUpdateSettings { it.copy(defaultExportFormat = fmt) } },
              label = { Text(fmt.label, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
        SettingSwitchRow(
          title = "Include Header & Timestamp Metadata",
          subtitle = "Embed target PDK, engine version, and SDC summary in exports",
          checked = settings.includeTimestampsInExport,
          onCheckedChange = { enabled -> onUpdateSettings { it.copy(includeTimestampsInExport = enabled) } },
        )
      }
    }
  }
}

@Composable
private fun SettingsGroupCard(
  icon: ImageVector,
  title: String,
  subtitle: String,
  content: @Composable () -> Unit,
) {
  EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
        Column {
          Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
          )
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      content()
    }
  }
}

@Composable
private fun SettingSwitchRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  testTag: String? = null,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
    )
  }
}
