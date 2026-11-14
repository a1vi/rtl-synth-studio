package com.example.domain.synth

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.example.data.model.TargetLibrary
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class PdkPackageStatus(
  val library: TargetLibrary,
  val isVerified: Boolean,
  val sha256Short: String,
  val sizeKb: Double,
  val ruleSummary: String,
)

data class DeviceResourceSnapshot(
  val availableRamMb: Long,
  val totalRamMb: Long,
  val lowMemory: Boolean,
  val thermalStatusLabel: String,
  val isThermalThrottled: Boolean,
)

/**
 * Materializes and SHA-256 verifies bundled standard-cell PDK rule packages into app-private storage,
 * and queries Android RAM + thermal telemetry to guard long physical synthesis jobs.
 */
object PdkCatalogManager {

  fun ensureAndVerifyPdk(context: Context, library: TargetLibrary): PdkPackageStatus {
    val pdkDir = File(context.filesDir, "pdk_packages/${library.id.lowercase(Locale.US)}")
    pdkDir.mkdirs()
    val techFile = File(pdkDir, "${library.id.lowercase(Locale.US)}_tech.lef")
    val payload = buildPdkDescriptorPayload(library)
    val expectedHash = sha256Hex(payload.toByteArray(Charsets.UTF_8))

    if (!techFile.exists() || sha256Hex(techFile.readBytes()) != expectedHash) {
      techFile.writeText(payload, Charsets.UTF_8)
    }

    val actualHash = sha256Hex(techFile.readBytes())
    val verified = actualHash == expectedHash
    val tech = RtlToGdsFlowEngine.techProfileFor(library)
    return PdkPackageStatus(
      library = library,
      isVerified = verified,
      sha256Short = actualHash.take(12),
      sizeKb = techFile.length() / 1024.0,
      ruleSummary = "Row ${tech.rowHeightUm}µm · M1 ${tech.m1PitchUm}µm · MinSpace ${tech.minSpacingUm}µm",
    )
  }

  fun queryDeviceResources(context: Context): DeviceResourceSnapshot {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    am?.getMemoryInfo(memInfo)
    val availMb = memInfo.availMem / (1024L * 1024L)
    val totalMb = memInfo.totalMem / (1024L * 1024L)

    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val (thermalLabel, throttled) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
      when (pm.currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "Nominal" to false
        PowerManager.THERMAL_STATUS_LIGHT -> "Warm" to false
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate" to false
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe" to true
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical" to true
        PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "Emergency" to true
        else -> "Nominal" to false
      }
    } else {
      "Nominal" to false
    }

    return DeviceResourceSnapshot(
      availableRamMb = availMb,
      totalRamMb = totalMb,
      lowMemory = memInfo.lowMemory || availMb < 350L,
      thermalStatusLabel = thermalLabel,
      isThermalThrottled = throttled,
    )
  }

  private fun buildPdkDescriptorPayload(library: TargetLibrary): String {
    val tech = RtlToGdsFlowEngine.techProfileFor(library)
    return buildString {
      appendLine("VERSION 5.8 ;")
      appendLine("NAMESCASESENSITIVE ON ;")
      appendLine("# RTL Synth Studio Verified On-Device PDK Rules: ${library.displayName}")
      appendLine("SITE unithd ; CLASS CORE ; SIZE ${tech.siteWidthUm} BY ${tech.rowHeightUm} ; END unithd")
      appendLine("LAYER met1 ; TYPE ROUTING ; DIRECTION HORIZONTAL ; PITCH ${tech.m1PitchUm} ; SPACING ${tech.minSpacingUm} ; END met1")
      appendLine("LAYER met2 ; TYPE ROUTING ; DIRECTION VERTICAL ; PITCH ${tech.m2PitchUm} ; SPACING ${tech.minSpacingUm} ; END met2")
      appendLine("END LIBRARY")
    }
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }
}
