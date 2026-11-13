package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Output
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.ParsedVerilogDesign
import com.example.domain.synth.ParsedVerilogModule
import com.example.domain.synth.PortDirection
import com.example.domain.synth.RtlPort
import com.example.domain.synth.VerilogLogicSignal
import com.example.domain.synth.VerilogModuleInstance
import com.example.domain.synth.VerilogParameter
import com.example.domain.synth.VerilogParseResult
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun ParsedRtlStructureCard(
  parsedDesign: ParsedVerilogDesign?,
  parseError: VerilogParseResult.Error?,
  onSelectTopModule: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(true) }

  val summaryText = when {
    parseError != null -> "Line ${parseError.line}:${parseError.column} syntax issue"
    parsedDesign != null -> {
      val top = parsedDesign.topModule
      val modLabel = if (parsedDesign.modules.size == 1) "1 module" else "${parsedDesign.modules.size} modules"
      "$modLabel · ${top.ports.size} ports · ${top.internalSignals.size} signals"
    }
    else -> "Ready to parse Verilog"
  }

  val accentColor = if (parseError != null) {
    MaterialTheme.colorScheme.error
  } else {
    MaterialTheme.colorScheme.secondary
  }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.large,
    modifier = modifier
      .fillMaxWidth()
      .border(
        width = 1.dp,
        color = if (parseError != null) {
          MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        } else {
          MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
        },
        shape = MaterialTheme.shapes.large,
      ),
  ) {
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = if (parseError != null) Icons.Rounded.ErrorOutline else Icons.Rounded.AccountTree,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(18.dp),
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Verilog HDL Parser",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = summaryText,
              style = MaterialTheme.typography.labelSmall,
              color = if (parseError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        IconButton(
          onClick = { expanded = !expanded },
          modifier = Modifier
            .size(48.dp)
            .testTag("parsed_rtl_toggle"),
        ) {
          Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Collapse parsed RTL structure" else "Expand parsed RTL structure",
          )
        }
      }

      AnimatedVisibility(visible = expanded) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (parseError != null) {
            ParseErrorDetailBlock(parseError)
          }

          if (parsedDesign != null) {
            ModuleHierarchySelector(
              modules = parsedDesign.modules,
              topModule = parsedDesign.topModule,
              onSelectTopModule = onSelectTopModule,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            PortsSection(ports = parsedDesign.topModule.ports)
            LogicSignalsSection(
              internalSignals = parsedDesign.topModule.internalSignals,
              parameters = parsedDesign.topModule.parameters,
            )
            if (parsedDesign.topModule.instances.isNotEmpty()) {
              SubmoduleInstancesSection(instances = parsedDesign.topModule.instances)
            }
            if (parsedDesign.diagnostics.isNotEmpty()) {
              ParserDiagnosticsSection(diagnostics = parsedDesign.diagnostics)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ParseErrorDetailBlock(error: VerilogParseResult.Error) {
  Surface(
    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
    shape = MaterialTheme.shapes.small,
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), MaterialTheme.shapes.small),
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = "Syntax Error at Line ${error.line}, Column ${error.column}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = error.message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (error.partialModules.isNotEmpty()) {
        Text(
          text = "Recovered module(s) before error: ${error.partialModules.joinToString { it.name }}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ModuleHierarchySelector(
  modules: List<ParsedVerilogModule>,
  topModule: ParsedVerilogModule,
  onSelectTopModule: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = if (modules.size > 1) "Modules (${modules.size} detected · Select Top)" else "Module",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = "Line ${topModule.line}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
      )
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      modules.forEach { mod ->
        val isTop = mod.name == topModule.name
        FilterChip(
          selected = isTop,
          onClick = { onSelectTopModule(mod.name) },
          label = {
            Text(
              text = if (isTop && modules.size > 1) "${mod.name} (top)" else mod.name,
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                fontWeight = if (isTop) FontWeight.SemiBold else FontWeight.Normal,
              ),
            )
          },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
          ),
          modifier = Modifier.testTag("top_module_chip_${mod.name}"),
        )
      }
    }
  }
}

@Composable
private fun PortsSection(ports: List<RtlPort>) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val inCount = ports.count { it.direction == PortDirection.INPUT }
    val outCount = ports.count { it.direction == PortDirection.OUTPUT }
    val inoutCount = ports.count { it.direction == PortDirection.INOUT }

    Text(
      text = "I/O Ports (${ports.size}: $inCount in, $outCount out" +
        (if (inoutCount > 0) ", $inoutCount inout)" else ")"),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (ports.isEmpty()) {
      Text(
        text = "No module ports declared.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ports.forEach { port ->
          PortRow(port = port)
        }
      }
    }
  }
}

@Composable
private fun PortRow(port: RtlPort) {
  val (dirLabel, dirColor, dirIcon) = when (port.direction) {
    PortDirection.INPUT -> Triple("IN", MaterialTheme.colorScheme.primary, Icons.AutoMirrored.Rounded.Input)
    PortDirection.OUTPUT -> Triple("OUT", MaterialTheme.colorScheme.tertiary, Icons.Rounded.Output)
    PortDirection.INOUT -> Triple("INOUT", MaterialTheme.colorScheme.secondary, Icons.Rounded.SwapHoriz)
  }

  val rangeText = if (port.width > 1 || port.msb != 0 || port.lsb != 0) {
    "[${port.msb}:${port.lsb}]"
  } else {
    "[0]"
  }

  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    shape = MaterialTheme.shapes.small,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = dirIcon,
        contentDescription = "${port.direction.name.lowercase()} port",
        tint = dirColor,
        modifier = Modifier.size(16.dp),
      )
      BadgePill(text = dirLabel, tint = dirColor)
      Text(
        text = port.name,
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = rangeText,
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 11.sp,
          color = MaterialTheme.colorScheme.primary,
        ),
      )
      if (port.isReg) {
        BadgePill(text = "reg", tint = MaterialTheme.colorScheme.secondary)
      }
      if (port.isSigned) {
        BadgePill(text = "signed", tint = MaterialTheme.colorScheme.tertiary)
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogicSignalsSection(
  internalSignals: List<VerilogLogicSignal>,
  parameters: List<VerilogParameter>,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = "Logic Signals & Parameters (${internalSignals.size} nets/regs, ${parameters.size} params)",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (internalSignals.isEmpty() && parameters.isEmpty()) {
      Text(
        text = "No internal wire, reg, logic, or parameter declarations.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
      )
    } else {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        parameters.forEach { param ->
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            shape = MaterialTheme.shapes.small,
          ) {
            Text(
              text = "${if (param.isLocalparam) "localparam" else "param"} ${param.name}=${param.value}",
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
              ),
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        }
        internalSignals.forEach { sig ->
          val widthTag = if (sig.width > 1 || sig.msb != 0 || sig.lsb != 0) " [${sig.msb}:${sig.lsb}]" else ""
          val signedTag = if (sig.isSigned) " signed" else ""
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            shape = MaterialTheme.shapes.small,
          ) {
            Text(
              text = "${sig.kind.label}$signedTag$widthTag ${sig.name}",
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
              ),
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SubmoduleInstancesSection(instances: List<VerilogModuleInstance>) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = "Submodule Instances (${instances.size})",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    instances.forEach { inst ->
      Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Memory,
            contentDescription = "Submodule instance",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = "${inst.instanceName} : ${inst.moduleType}",
            style = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          val connCount = inst.portConnections.size + inst.positionalConnections.size
          Text(
            text = "$connCount pins",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun ParserDiagnosticsSection(diagnostics: List<String>) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    diagnostics.forEach { diag ->
      Text(
        text = "[PARSER] $diag",
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 11.sp,
          color = MaterialTheme.colorScheme.secondary,
        ),
      )
    }
  }
}

@Composable
private fun BadgePill(text: String, tint: Color) {
  Surface(
    color = tint.copy(alpha = 0.14f),
    shape = MaterialTheme.shapes.extraSmall,
  ) {
    Text(
      text = text,
      style = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = tint,
      ),
      modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
    )
  }
}
