package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.OptimizationGoal
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import com.example.domain.synth.TimingFixAction
import com.example.domain.synth.TimingRemediationKind
import com.example.domain.synth.TimingRemediationPlan
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimingViolationRemediationCard(
  plan: TimingRemediationPlan,
  onApplyClockPeriod: (Double) -> Unit,
  onApplyOptimizationGoal: (OptimizationGoal) -> Unit,
  onApplySynthesisEngine: (SynthesisEngineProfile) -> Unit,
  onApplyTargetLibrary: (TargetLibrary) -> Unit,
  onJumpToRtlLine: (Int) -> Unit,
  onInspectWorstPath: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val wnsFormatted = if (plan.wnsNs >= 0.0) {
    "+${"%.3f".format(Locale.US, plan.wnsNs)} ns"
  } else {
    "${"%.3f".format(Locale.US, plan.wnsNs)} ns"
  }

  EdaSurfaceCard(
    modifier = modifier
      .fillMaxWidth()
      .testTag("timing_violation_remediation_card"),
  ) {
    Column {
      EdaPanelHeader(
        title = "Timing Closure Action Plan",
        subtitle = "What to fix · ${plan.violatingPathCount} violating path(s)",
        icon = Icons.Rounded.Build,
        trailing = {
          EdaStatusPill(
            text = "WNS $wnsFormatted",
            color = eda.error,
            containerColor = eda.errorContainer,
            showDot = true,
          )
        },
      )

      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // Root-cause diagnostic banner
        Surface(
          color = eda.errorContainer.copy(alpha = 0.45f),
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, eda.error.copy(alpha = 0.45f)),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("timing_violation_diagnosis_box"),
        ) {
          Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = eda.error,
                modifier = Modifier.size(16.dp),
              )
              Text(
                text = "Why timing violated",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = eda.error,
              )
            }
            Text(
              text = "Path ${plan.bottleneckPathSummary} exceeds ${"%.2f".format(Locale.US, plan.currentClockNs)} ns clock constraint across ${plan.logicLevels} logic level(s).",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
            EdaKeyValueRow(
              label = "Bottleneck Stage",
              value = "${plan.bottleneckCellLabel} (+${"%.3f".format(Locale.US, plan.bottleneckStageDelayNs)} ns)",
            )
            EdaKeyValueRow(
              label = "Target to Close",
              value = "≥ ${"%.2f".format(Locale.US, plan.recommendedClockNs)} ns (Est. ${"%.1f".format(Locale.US, plan.estimatedFmaxMhz)} MHz)",
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Recommended Actions (in priority order)",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
          )
          if (onInspectWorstPath != null) {
            OutlinedButton(
              onClick = onInspectWorstPath,
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("remediation_trace_path_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.AccountTree,
                contentDescription = "Trace violating critical path in schematic",
                modifier = Modifier.size(15.dp),
              )
              Spacer(Modifier.width(4.dp))
              Text("Trace Path", style = MaterialTheme.typography.labelSmall)
            }
          }
        }

        plan.recommendations.forEachIndexed { idx, action ->
          RemediationStepCard(
            stepNumber = idx + 1,
            action = action,
            onApplyClockPeriod = onApplyClockPeriod,
            onApplyOptimizationGoal = onApplyOptimizationGoal,
            onApplySynthesisEngine = onApplySynthesisEngine,
            onApplyTargetLibrary = onApplyTargetLibrary,
            onJumpToRtlLine = onJumpToRtlLine,
          )
        }
      }
    }
  }
}

@Composable
private fun RemediationStepCard(
  stepNumber: Int,
  action: TimingFixAction,
  onApplyClockPeriod: (Double) -> Unit,
  onApplyOptimizationGoal: (OptimizationGoal) -> Unit,
  onApplySynthesisEngine: (SynthesisEngineProfile) -> Unit,
  onApplyTargetLibrary: (TargetLibrary) -> Unit,
  onJumpToRtlLine: (Int) -> Unit,
) {
  val eda = LocalEdaColors.current
  val icon = when (action.kind) {
    TimingRemediationKind.OPTIMIZE_FOR_SPEED -> Icons.Rounded.Speed
    TimingRemediationKind.SWITCH_TIMING_ENGINE -> Icons.Rounded.Tune
    TimingRemediationKind.RELAX_CLOCK_PERIOD -> Icons.Rounded.Schedule
    TimingRemediationKind.PIPELINE_BOTTLENECK_RTL -> Icons.Rounded.Code
    TimingRemediationKind.EVALUATE_FASTER_PDK -> Icons.Rounded.Timeline
    TimingRemediationKind.FIX_HOLD_VIOLATION -> Icons.Rounded.Build
  }
  val actionTag = "remediation_action_${action.kind.name.lowercase(Locale.ROOT)}"

  Surface(
    color = eda.elevatedCard,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.7f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("remediation_step_${action.kind.name.lowercase(Locale.ROOT)}"),
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
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
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "$stepNumber.",
            style = MaterialTheme.typography.labelMedium.copy(
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.primary,
          )
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = action.title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
          shape = MaterialTheme.shapes.extraSmall,
        ) {
          Text(
            text = action.impactBadge,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }

      Text(
        text = action.detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      val actionLabel = action.actionLabel
      if (actionLabel != null) {
        val onClick: () -> Unit = {
          when {
            action.targetGoal != null -> onApplyOptimizationGoal(action.targetGoal)
            action.targetEngine != null -> onApplySynthesisEngine(action.targetEngine)
            action.targetClockNs != null -> onApplyClockPeriod(action.targetClockNs)
            action.targetLibrary != null -> onApplyTargetLibrary(action.targetLibrary)
            action.rtlLine != null -> onJumpToRtlLine(action.rtlLine)
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          val isPrimary = action.kind == TimingRemediationKind.OPTIMIZE_FOR_SPEED ||
            action.kind == TimingRemediationKind.RELAX_CLOCK_PERIOD
          if (isPrimary) {
            Button(
              onClick = onClick,
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
              ),
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(actionTag),
            ) {
              Text(actionLabel, style = MaterialTheme.typography.labelSmall)
            }
          } else {
            OutlinedButton(
              onClick = onClick,
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(actionTag),
            ) {
              Text(actionLabel, style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimingViolationCalloutStrip(
  plan: TimingRemediationPlan,
  onApplyClockPeriod: ((Double) -> Unit)? = null,
  onApplyOptimizationGoal: ((OptimizationGoal) -> Unit)? = null,
  onOpenTimingGuide: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val showMinDelay = plan.recommendations.any { it.kind == TimingRemediationKind.OPTIMIZE_FOR_SPEED }
  val canRelaxClock = plan.recommendedClockNs > plan.currentClockNs + 0.01

  Surface(
    color = eda.errorContainer.copy(alpha = 0.4f),
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, eda.error.copy(alpha = 0.45f)),
    modifier = modifier
      .fillMaxWidth()
      .testTag("synth_timing_violation_callout"),
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.WarningAmber,
          contentDescription = null,
          tint = eda.error,
          modifier = Modifier.size(15.dp),
        )
        Text(
          text = "Timing Violated (${"%.3f".format(Locale.US, plan.wnsNs)} ns) · What to do:",
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
          color = eda.error,
        )
      }
      Text(
        text = buildString {
          append("Bottleneck: ${plan.bottleneckCellLabel} (${plan.logicLevels} levels). ")
          if (showMinDelay) append("Switch to Min Delay, ")
          append("pipeline critical RTL logic")
          if (canRelaxClock) {
            append(", or relax clock to ≥ ${"%.2f".format(Locale.US, plan.recommendedClockNs)} ns.")
          } else {
            append(".")
          }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (onApplyOptimizationGoal != null || onApplyClockPeriod != null || onOpenTimingGuide != null) {
        HorizontalDivider(color = eda.error.copy(alpha = 0.2f))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          if (showMinDelay && onApplyOptimizationGoal != null) {
            Button(
              onClick = { onApplyOptimizationGoal(OptimizationGoal.MIN_DELAY) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("callout_fix_min_delay"),
            ) {
              Text("Fix: Min Delay", style = MaterialTheme.typography.labelSmall)
            }
          }
          if (canRelaxClock && onApplyClockPeriod != null) {
            OutlinedButton(
              onClick = { onApplyClockPeriod(plan.recommendedClockNs) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("callout_fix_clock"),
            ) {
              Text(
                "Set ${"%.2f".format(Locale.US, plan.recommendedClockNs)} ns",
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
          if (onOpenTimingGuide != null) {
            OutlinedButton(
              onClick = onOpenTimingGuide,
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("callout_open_sta_guide"),
            ) {
              Text("View Timing Fixes", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }
  }
}
