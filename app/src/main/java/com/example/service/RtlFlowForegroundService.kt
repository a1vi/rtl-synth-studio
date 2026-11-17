package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Foreground Service keeping long RTL-to-GDSII synthesis and PDK fetch jobs alive across
 * screen-off and background app switching, backed by a bounded partial WakeLock and
 * a Cancel action in the notification shade.
 */
class RtlFlowForegroundService : Service() {

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
    acquireBoundedWakeLock()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CANCEL -> {
        _cancelRequests.tryEmit(Unit)
        stopSelfSafe()
        return START_NOT_STICKY
      }
      ACTION_STOP -> {
        stopSelfSafe()
        return START_NOT_STICKY
      }
      else -> {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Running RTL-to-GDSII Flow"
        val detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "Executing synthesis & physical PnR"
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
        val total = intent?.getIntExtra(EXTRA_TOTAL, 7) ?: 7
        val notification = buildNotification(title, detail, progress, total)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
          startForeground(NOTIFICATION_ID, notification)
        }
        acquireBoundedWakeLock()
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    releaseWakeLock()
    super.onDestroy()
  }

  private fun stopSelfSafe() {
    releaseWakeLock()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    stopSelf()
  }

  private fun acquireBoundedWakeLock() {
    if (wakeLock?.isHeld == true) return
    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
    val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RtlSynthStudio::FlowWakeLock").apply {
      setReferenceCounted(false)
    }
    runCatching { wl.acquire(10 * 60_000L) }
    wakeLock = wl
  }

  private fun releaseWakeLock() {
    runCatching {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
    }
    wakeLock = null
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
      if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
        val channel = NotificationChannel(
          CHANNEL_ID,
          "RTL Flow & PDK Sync",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Shows progress for long RTL-to-GDSII runs and PDK package downloads."
        }
        mgr.createNotificationChannel(channel)
      }
    }
  }

  private fun buildNotification(
    title: String,
    detail: String,
    progress: Int,
    total: Int,
  ): Notification {
    val openIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentPending = PendingIntent.getActivity(
      this,
      0,
      openIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val cancelIntent = Intent(this, RtlFlowForegroundService::class.java).apply {
      action = ACTION_CANCEL
    }
    val cancelPending = PendingIntent.getService(
      this,
      1,
      cancelIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val safeTotal = total.coerceAtLeast(1)
    val safeProgress = progress.coerceIn(0, safeTotal)

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(title)
      .setContentText(detail)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setProgress(safeTotal, safeProgress, safeProgress == 0)
      .setContentIntent(contentPending)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)
      .build()
  }

  companion object {
    const val CHANNEL_ID = "rtl_flow_channel"
    const val NOTIFICATION_ID = 4107
    const val ACTION_START_OR_UPDATE = "com.example.service.ACTION_START_OR_UPDATE"
    const val ACTION_STOP = "com.example.service.ACTION_STOP"
    const val ACTION_CANCEL = "com.example.service.ACTION_CANCEL"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_DETAIL = "extra_detail"
    const val EXTRA_PROGRESS = "extra_progress"
    const val EXTRA_TOTAL = "extra_total"

    private val _cancelRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val cancelRequests: SharedFlow<Unit> = _cancelRequests.asSharedFlow()

    fun startOrUpdate(
      context: Context,
      title: String,
      detail: String,
      progress: Int,
      total: Int = 7,
    ) {
      runCatching {
        val intent = Intent(context, RtlFlowForegroundService::class.java).apply {
          action = ACTION_START_OR_UPDATE
          putExtra(EXTRA_TITLE, title)
          putExtra(EXTRA_DETAIL, detail)
          putExtra(EXTRA_PROGRESS, progress)
          putExtra(EXTRA_TOTAL, total)
        }
        ContextCompat.startForegroundService(context, intent)
      }
    }

    fun stop(context: Context) {
      runCatching {
        val intent = Intent(context, RtlFlowForegroundService::class.java).apply {
          action = ACTION_STOP
        }
        context.startService(intent)
      }
    }
  }
}
