package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.RtlDesign
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlPresetTemplate
import com.example.ui.viewmodel.UiState
import java.util.Locale

@Composable
fun LibraryScreen(
  userEmail: String?,
  designsState: UiState<List<RtlDesign>>,
  runsState: UiState<List<SynthesisRun>>,
  onLoadDesign: (RtlDesign) -> Unit,
  onLoadPreset: (RtlPresetTemplate) -> Unit,
  onNewBlankDesign: () -> Unit,
  onDeleteDesign: (String) -> Unit,
  onDeleteRun: (String) -> Unit,
  onSignOut: () -> Unit,
  modifier: Modifier = Modifier,
  isOfflineMode: Boolean = false,
  pendingLocalCount: Int = 0,
  onSyncLocalToCloud: () -> Unit = {},
  onSignInToCloud: () -> Unit = {},
) {
  var selectedTab by remember { mutableIntStateOf(0) } // 0 = Saved Designs, 1 = Presets, 2 = PPA Runs

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Account & New Module Bar
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 1.dp,
      shape = MaterialTheme.shapes.large,
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.large),
    ) {
      Row(
        modifier = Modifier.padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          Text(
            text = if (isOfflineMode) "Local Offline Workspace" else "Cloud RTL Workspace",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = if (isOfflineMode) {
              "Stored on device · Sign in anytime to sync"
            } else {
              userEmail ?: "Signed in with Google"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = onNewBlankDesign,
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("new_blank_rtl_button"),
          ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("New", style = MaterialTheme.typography.labelLarge)
          }
          if (isOfflineMode) {
            OutlinedButton(
              onClick = onSignInToCloud,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("library_sign_in_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.Login,
                contentDescription = "Sign in with Google to sync",
                modifier = Modifier.size(18.dp),
              )
            }
          } else {
            OutlinedButton(
              onClick = onSignOut,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("sign_out_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.Logout,
                contentDescription = "Sign out",
                modifier = Modifier.size(18.dp),
              )
            }
          }
        }
      }
    }

    if (!isOfflineMode && pendingLocalCount > 0) {
      Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
          .fillMaxWidth()
          .border(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            MaterialTheme.shapes.medium,
          ),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.CloudUpload,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = "$pendingLocalCount local offline module(s) ready to sync",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
          Button(
            onClick = onSyncLocalToCloud,
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("sync_local_designs_button"),
          ) {
            Text("Sync", style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      SegmentedButton(
        selected = selectedTab == 0,
        onClick = { selectedTab = 0 },
        shape = SegmentedButtonDefaults.itemShape(0, 3),
        modifier = Modifier.testTag("library_tab_cloud"),
      ) {
        Text(if (isOfflineMode) "Local Designs" else "My Designs", style = MaterialTheme.typography.labelMedium)
      }
      SegmentedButton(
        selected = selectedTab == 1,
        onClick = { selectedTab = 1 },
        shape = SegmentedButtonDefaults.itemShape(1, 3),
        modifier = Modifier.testTag("library_tab_presets"),
      ) {
        Text("Presets", style = MaterialTheme.typography.labelMedium)
      }
      SegmentedButton(
        selected = selectedTab == 2,
        onClick = { selectedTab = 2 },
        shape = SegmentedButtonDefaults.itemShape(2, 3),
        modifier = Modifier.testTag("library_tab_runs"),
      ) {
        Text("PPA Runs", style = MaterialTheme.typography.labelMedium)
      }
    }

    when (selectedTab) {
      0 -> CloudDesignsList(
        state = designsState,
        isOfflineMode = isOfflineMode,
        onLoadDesign = onLoadDesign,
        onDeleteDesign = onDeleteDesign,
        onNewBlankDesign = onNewBlankDesign,
        modifier = Modifier.weight(1f),
      )
      1 -> PresetsList(
        onLoadPreset = onLoadPreset,
        modifier = Modifier.weight(1f),
      )
      2 -> SynthesisRunsList(
        state = runsState,
        onDeleteRun = onDeleteRun,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun CloudDesignsList(
  state: UiState<List<RtlDesign>>,
  isOfflineMode: Boolean,
  onLoadDesign: (RtlDesign) -> Unit,
  onDeleteDesign: (String) -> Unit,
  onNewBlankDesign: () -> Unit,
  modifier: Modifier = Modifier,
) {
  when (state) {
    UiState.Loading -> {
      Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(10.dp))
        Text("Syncing cloud RTL designs...", style = MaterialTheme.typography.bodySmall)
      }
    }
    is UiState.Error -> {
      Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = state.message,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(16.dp),
        )
      }
    }
    is UiState.Success -> {
      if (state.data.isEmpty()) {
        Surface(
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 1.dp,
          shape = MaterialTheme.shapes.large,
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.large),
        ) {
          Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.CloudDone,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(44.dp),
            )
            Text(
              text = if (isOfflineMode) "No Saved Local Designs Yet" else "No Saved Cloud Designs Yet",
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = if (isOfflineMode) {
                "Edit any preset in Workbench and tap Save to store it offline, or start a blank RTL module."
              } else {
                "Edit any preset in Workbench and tap Save Cloud, or start a blank RTL module."
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Button(onClick = onNewBlankDesign, modifier = Modifier.heightIn(min = 48.dp)) {
              Text("Create Blank RTL Module")
            }
          }
        }
      } else {
        LazyColumn(
          modifier = modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(state.data, key = { it.id }) { design ->
            val lib = TargetLibrary.fromId(design.targetLibrary)
            Surface(
              color = MaterialTheme.colorScheme.surface,
              tonalElevation = 1.dp,
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.medium),
            ) {
              Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(
                  modifier = Modifier.weight(1f),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Text(
                    text = design.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Text(
                    text = "${lib.shortName} · ${design.lastCellCount} cells · ${String.format(Locale.US, "%.2f", design.lastAreaUm2)} µm² · WNS ${if (design.lastWnsNs >= 0) "+" else ""}${String.format(Locale.US, "%.2f", design.lastWnsNs)} ns",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  if (design.description.isNotBlank()) {
                    Text(
                      text = design.description,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
                Row {
                  IconButton(
                    onClick = { onLoadDesign(design) },
                    modifier = Modifier.size(48.dp),
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.FolderOpen,
                      contentDescription = "Open ${design.name} in workbench",
                      tint = MaterialTheme.colorScheme.primary,
                    )
                  }
                  IconButton(
                    onClick = { onDeleteDesign(design.id) },
                    modifier = Modifier.size(48.dp),
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.DeleteOutline,
                      contentDescription = "Delete ${design.name}",
                      tint = MaterialTheme.colorScheme.error,
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
}

@Composable
private fun PresetsList(
  onLoadPreset: (RtlPresetTemplate) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    items(PresetRtlDesigns.templates, key = { it.id }) { preset ->
      Surface(
        onClick = { onLoadPreset(preset) },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.medium),
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Memory,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
          )
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(preset.name, style = MaterialTheme.typography.titleMedium)
              Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.extraSmall,
              ) {
                Text(
                  text = preset.category,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            }
            Text(
              text = preset.description,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = "Default: ${preset.defaultLibrary.shortName} · ${preset.defaultClockNs} ns",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.secondary,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SynthesisRunsList(
  state: UiState<List<SynthesisRun>>,
  onDeleteRun: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  when (state) {
    UiState.Loading -> {
      Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator()
      }
    }
    is UiState.Error -> {
      Text(state.message, color = MaterialTheme.colorScheme.error)
    }
    is UiState.Success -> {
      if (state.data.isEmpty()) {
        Surface(
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 1.dp,
          shape = MaterialTheme.shapes.large,
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.large),
        ) {
          Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.History,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.size(44.dp),
            )
            Text("No PPA Snapshots Recorded", style = MaterialTheme.typography.titleMedium)
            Text(
              text = "Save a PPA Run Snapshot from the Timing & PPA tab to compare metrics across technology libraries.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
          }
        }
      } else {
        LazyColumn(
          modifier = modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(state.data, key = { it.id }) { run ->
            val lib = TargetLibrary.fromId(run.targetLibrary)
            val met = run.wnsNs >= 0.0
            Surface(
              color = MaterialTheme.colorScheme.surface,
              tonalElevation = 1.dp,
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.medium),
            ) {
              Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(run.designName, style = MaterialTheme.typography.titleMedium)
                    Text(
                      text = lib.shortName,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                    )
                  }
                  Text(
                    text = "Fmax ${String.format(Locale.US, "%.1f", run.fmaxMhz)} MHz · WNS ${if (run.wnsNs >= 0) "+" else ""}${String.format(Locale.US, "%.2f", run.wnsNs)} ns · Area ${String.format(Locale.US, "%.2f", run.areaUm2)} µm²",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (met) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                  )
                  Text(
                    text = "${run.cellCount} cells (${run.lutCount} comb / ${run.dffCount} seq) · ${String.format(Locale.US, "%.3f", run.powerMw)} mW · ${run.optimizationGoal}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                IconButton(
                  onClick = { onDeleteRun(run.id) },
                  modifier = Modifier.size(48.dp),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete synthesis snapshot",
                    tint = MaterialTheme.colorScheme.error,
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
