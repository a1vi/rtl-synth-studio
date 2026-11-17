package com.example.domain.synth

import com.example.data.model.TargetLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class RemoteHealthResult(
  val ok: Boolean,
  val message: String,
  val tools: List<String> = emptyList(),
)

data class RemoteFlowResult(
  val success: Boolean,
  val message: String,
  val logs: List<String> = emptyList(),
  val wnsNs: Double? = null,
  val areaUm2: Double? = null,
  val cellCount: Int? = null,
  val fmaxMhz: Double? = null,
  val gdsBase64: String? = null,
)

/**
 * Real HTTP/JSON client connecting to an external user-hosted OpenLane / OpenROAD / Yosys
 * workstation daemon (`GET /health`, `POST /api/v1/flow`).
 */
object RemoteWorkstationClient {

  suspend fun checkHealth(baseUrl: String): RemoteHealthResult = withContext(Dispatchers.IO) {
    val cleanBase = normalizeBaseUrl(baseUrl)
      ?: return@withContext RemoteHealthResult(false, "Invalid endpoint URL: $baseUrl")

    var conn: HttpURLConnection? = null
    try {
      conn = (URL("$cleanBase/health").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 4000
        readTimeout = 4000
      }
      val code = conn.responseCode
      val body = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
      if (code !in 200..299) {
        return@withContext RemoteHealthResult(false, "Worker returned HTTP $code")
      }
      val json = runCatching { JSONObject(body) }.getOrNull()
      val tools = mutableListOf<String>()
      val toolsArr = json?.optJSONArray("tools")
      if (toolsArr != null) {
        for (i in 0 until toolsArr.length()) {
          tools += toolsArr.optString(i)
        }
      }
      val statusMsg = json?.optString("status")?.takeIf { it.isNotBlank() } ?: "Connected (HTTP $code)"
      RemoteHealthResult(ok = true, message = statusMsg, tools = tools.filter { it.isNotBlank() })
    } catch (e: Exception) {
      RemoteHealthResult(false, "Unreachable (${e.javaClass.simpleName}: ${e.localizedMessage ?: "connection refused"})")
    } finally {
      conn?.disconnect()
    }
  }

  suspend fun submitRtlFlow(
    baseUrl: String,
    moduleName: String,
    library: TargetLibrary,
    clockPeriodNs: Double,
    utilizationPct: Int,
    verilogSource: String,
    sdcText: String,
  ): RemoteFlowResult = withContext(Dispatchers.IO) {
    val cleanBase = normalizeBaseUrl(baseUrl)
      ?: return@withContext RemoteFlowResult(false, "Invalid endpoint URL: $baseUrl")

    var conn: HttpURLConnection? = null
    try {
      val payload = JSONObject().apply {
        put("topModule", moduleName)
        put("pdk", library.id)
        put("clockPeriodNs", clockPeriodNs)
        put("coreUtilization", utilizationPct)
        put("rtlSource", verilogSource)
        put("sdc", sdcText)
      }.toString()

      conn = (URL("$cleanBase/api/v1/flow").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 5000
        readTimeout = 60_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
      }
      OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
        writer.write(payload)
        writer.flush()
      }

      val code = conn.responseCode
      val body = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
      if (code !in 200..299) {
        return@withContext RemoteFlowResult(
          success = false,
          message = "Remote OpenROAD worker returned HTTP $code: ${body.take(180)}",
        )
      }

      val json = runCatching { JSONObject(body) }.getOrNull()
        ?: return@withContext RemoteFlowResult(false, "Malformed JSON response from worker.")
      val ok = json.optBoolean("success", true)
      val logsList = mutableListOf<String>()
      val logsArr: JSONArray? = json.optJSONArray("logs")
      if (logsArr != null) {
        for (i in 0 until logsArr.length()) {
          logsList += logsArr.optString(i)
        }
      }
      RemoteFlowResult(
        success = ok,
        message = json.optString("message", if (ok) "Remote signoff flow completed." else "Remote flow failed."),
        logs = logsList,
        wnsNs = if (json.has("wnsNs")) json.optDouble("wnsNs") else null,
        areaUm2 = if (json.has("areaUm2")) json.optDouble("areaUm2") else null,
        cellCount = if (json.has("cellCount")) json.optInt("cellCount") else null,
        fmaxMhz = if (json.has("fmaxMhz")) json.optDouble("fmaxMhz") else null,
        gdsBase64 = json.optString("gdsBase64").takeIf { it.isNotBlank() },
      )
    } catch (e: Exception) {
      RemoteFlowResult(
        success = false,
        message = "Remote worker unavailable (${e.localizedMessage ?: e.javaClass.simpleName})",
      )
    } finally {
      conn?.disconnect()
    }
  }

  private fun normalizeBaseUrl(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isBlank()) return null
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
    return trimmed
  }

  private fun readStream(stream: java.io.InputStream?): String {
    if (stream == null) return ""
    return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
  }
}
