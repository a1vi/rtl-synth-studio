package com.example.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.R

@Composable
fun AuthScreen(
  onAuthSuccess: () -> Unit,
  onContinueOffline: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }

  var isLoading by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    attemptAutoSignIn(
      context = context,
      credentialManager = credentialManager,
      onAuthSuccess = onAuthSuccess,
      onUnauthenticated = { /* Wait for interactive button tap */ },
      scope = scope,
    )
  }

  val bgColor = MaterialTheme.colorScheme.background
  val primary = MaterialTheme.colorScheme.primary
  val secondary = MaterialTheme.colorScheme.secondary
  val outline = MaterialTheme.colorScheme.outline

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(
            bgColor,
            MaterialTheme.colorScheme.surface,
            bgColor,
          )
        )
      )
      .windowInsetsPadding(WindowInsets.safeDrawing)
  ) {
    // Silicon die grid backdrop
    Canvas(modifier = Modifier.fillMaxSize()) {
      val step = 36.dp.toPx()
      var x = 0f
      while (x < size.width) {
        drawLine(
          color = outline.copy(alpha = 0.22f),
          start = Offset(x, 0f),
          end = Offset(x, size.height),
          strokeWidth = 1f,
        )
        x += step
      }
      var y = 0f
      while (y < size.height) {
        drawLine(
          color = outline.copy(alpha = 0.22f),
          start = Offset(0f, y),
          end = Offset(size.width, y),
          strokeWidth = 1f,
        )
        y += step
      }
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(primary.copy(alpha = 0.14f), Color.Transparent),
          center = Offset(size.width * 0.2f, size.height * 0.18f),
          radius = size.width * 0.65f,
        ),
        radius = size.width * 0.65f,
        center = Offset(size.width * 0.2f, size.height * 0.18f),
      )
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(secondary.copy(alpha = 0.10f), Color.Transparent),
          center = Offset(size.width * 0.85f, size.height * 0.72f),
          radius = size.width * 0.5f,
        ),
        radius = size.width * 0.5f,
        center = Offset(size.width * 0.85f, size.height * 0.72f),
      )
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(24.dp),
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
          modifier = Modifier.border(
            1.dp,
            primary.copy(alpha = 0.4f),
            RoundedCornerShape(24.dp),
          ),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.Memory,
              contentDescription = null,
              tint = primary,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = "VERILOG HDL → GATE NETLIST",
              style = MaterialTheme.typography.labelMedium,
              color = primary,
            )
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
          )
          Text(
            text = stringResource(R.string.auth_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          FeatureRow(
            icon = Icons.Rounded.AccountTree,
            tint = primary,
            title = "Technology Mapping & Gate Schematic",
            subtitle = "Map Verilog RTL to ASAP7 7nm, Sky130 130nm, or Artix-7 LUT6 primitives with zoomable schematic inspection.",
          )
          FeatureRow(
            icon = Icons.Rounded.Speed,
            tint = secondary,
            title = "Static Timing, SDC & Simulation",
            subtitle = "Run SDC-constrained STA, view waveforms on-device, and inspect WNS, TNS, area, and power.",
          )
          FeatureRow(
            icon = Icons.Rounded.CloudSync,
            tint = MaterialTheme.colorScheme.tertiary,
            title = "Offline-First + Cloud RTL Library",
            subtitle = "Draft locally offline or sign in with Google to sync modules and PPA snapshots across devices.",
          )
        }

        if (errorMessage != null) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = errorMessage ?: "",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(14.dp),
            )
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Button(
            onClick = {
              errorMessage = null
              isLoading = true
              onGoogleSignInClicked(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {
                  isLoading = false
                  onAuthSuccess()
                },
                onAuthError = { msg ->
                  isLoading = false
                  errorMessage = msg
                },
                scope = scope,
                onAuthCancelled = {
                  isLoading = false
                },
              )
            },
            enabled = !isLoading,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
              containerColor = primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
              .fillMaxWidth()
              .height(54.dp)
              .testTag("google_sign_in_button"),
          ) {
            if (isLoading) {
              CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.5.dp,
              )
              Spacer(Modifier.width(12.dp))
              Text(
                text = "Authenticating...",
                style = MaterialTheme.typography.labelLarge,
              )
            } else {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.Login,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
              )
              Spacer(Modifier.width(10.dp))
              Text(
                text = stringResource(R.string.sign_in_with_google),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
              )
            }
          }

          if (onContinueOffline != null) {
            androidx.compose.material3.OutlinedButton(
              onClick = onContinueOffline,
              enabled = !isLoading,
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("continue_offline_button"),
            ) {
              Text(
                text = "Continue Offline (Local Workspace)",
                style = MaterialTheme.typography.labelLarge,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun FeatureRow(
  icon: ImageVector,
  tint: Color,
  title: String,
  subtitle: String,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.medium),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Surface(
        color = tint.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
          )
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
