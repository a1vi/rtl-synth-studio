package com.example.ui.screens.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.EdaStatusPill
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import com.example.ui.viewmodel.WorkbenchUiState
import java.util.Locale

@Composable
fun AnalysisContextHeader(
  state: WorkbenchUiState,
  onRunSynthesis: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val report = (state.synthesisResult as? SynthesisResult.Success)?.report
  val error = state.synthesisResult as? SynthesisResult.Error

  val (statusText, statusColor, statusBg) = when {
    state.isSynthesizing -> Triple("RUNNING", eda.warning, eda.warningContainer)
    error != null -> Triple("SYNTH ERR", eda.error, eda.errorContainer)
    report == null -> Triple("NOT RUN", eda.cyanHighlight, eda.cyanContainer)
    state.isResultOutdated -> Triple("OUTDATED", eda.warning, eda.warningContainer)
    report.wnsNs >= 0.0 -> Triple("MET", eda.success, eda.successContainer)
    else -> Triple("VIOLATED", eda.error, eda.errorContainer)
  }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = MaterialTheme.shapes.medium,
    border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.7f)),
    modifier = modifier.fillMaxWidth().testTag("analysis_context_header"),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier.weight(1f).padding(end = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Memory,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Column {
            Text(
              text = "${state.designName.ifBlank { "rtl_module" }} · ${state.selectedTopModule ?: report?.moduleName ?: "top"}",
              style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.SemiBold,
              ),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            val runBadge = if (state.synthesisRunSequence > 0) "Run #${state.synthesisRunSequence}" else "No Run"
            Text(
              text = "${state.targetLibrary.shortName} · ${state.optimizationGoal.displayName} · $runBadge",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        EdaStatusPill(
          text = statusText,
          color = statusColor,
          containerColor = statusBg,
          showDot = true,
        )
      }

      if (state.isResultOutdated && report != null && !state.isSynthesizing) {
        Surface(
          color = eda.warningContainer.copy(alpha = 0.7f),
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, eda.warning.copy(alpha = 0.45f)),
          modifier = Modifier.fillMaxWidth().testTag("analysis_outdated_banner"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              modifier = Modifier.weight(1f).padding(end = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = eda.warning, modifier = Modifier.size(15.dp))
              Text(
                text = "RTL or constraints changed after last synthesis.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            TextButton(
              onClick = onRunSynthesis,
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
              modifier = Modifier.heightIn(min = 36.dp).testTag("analysis_resynth_button"),
            ) {
              Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(Modifier.width(3.dp))
              Text("Re-Synth", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }
  }
}

@Composable
fun EdaStackedRatioBar(
  segments: List<Triple<String, Double, Color>>,
  modifier: Modifier = Modifier,
) {
  val total = segments.sumOf { it.second.coerceAtLeast(0.0) }
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(14.dp),
    ) {
      if (total <= 1e-9) {
        drawRoundRect(
          color = Color.Gray.copy(alpha = 0.25f),
          size = size,
          cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        )
        return@Canvas
      }
      var xOffset = 0f
      segments.forEach { (_, value, color) ->
        val w = ((value.coerceAtLeast(0.0) / total) * size.width).toFloat()
        if (w > 0.5f) {
          drawRect(
            color = color,
            topLeft = Offset(xOffset, 0f),
            size = Size(w, size.height),
          )
          xOffset += w
        }
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      segments.forEach { (label, value, color) ->
        val pct = if (total > 1e-9) (value / total) * 100.0 else 0.0
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Surface(color = color, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.size(8.dp)) {}
          Text(
            text = "$label ${"%.1f".format(Locale.US, pct)}%",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

fun formatPowerAuto(powerMw: Double): String {
  val absVal = kotlin.math.abs(powerMw)
  return when {
    absVal >= 1000.0 -> "${"%.3f".format(Locale.US, powerMw / 1000.0)} W"
    absVal >= 0.1 -> "${"%.3f".format(Locale.US, powerMw)} mW"
    else -> "${"%.2f".format(Locale.US, powerMw * 1000.0)} µW"
  }
}

fun formatSignedNs(value: Double): String =
  if (value >= 0.0) "+${"%.3f".format(Locale.US, value)} ns" else "${"%.3f".format(Locale.US, value)} ns"

fun formatAreaMetric(areaUm2: Double, isFpga: Boolean): String =
  if (isFpga) "${"%.1f".format(Locale.US, areaUm2)} slice" else "${"%.2f".format(Locale.US, areaUm2)} µm²"

@Composable
fun AnalysisQuickSyncButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = 48.dp),
    shape = MaterialTheme.shapes.small,
  ) {
    Text(label, style = MaterialTheme.typography.labelMedium)
  }
}

@Suppress("unused")
internal fun reportHasSynthesizedCells(report: SynthesisReport?): Boolean =
  report != null && report.cellCount > 0
