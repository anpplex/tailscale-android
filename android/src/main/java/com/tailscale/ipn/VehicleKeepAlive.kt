// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.UninitializedPropertyAccessException

/**
 * Cabin keep-alive modeled on GalaxyScreen. Broadcasts, the provider, the job, and the
 * notification bridge all enter [VehicleKeepAlive.wake], which starts [VehicleKeepAliveService].
 * That service is the sticky foreground anchor. Its alarms use [PendingIntent.getForegroundService]
 * so a dead process is resumed as a foreground service. The VPN itself still starts through
 * [UninitializedApp.startVPN] once this process is in the foreground.
 */
object VehicleKeepAlive {
  private const val TAG = "VehicleKeepAlive"
  const val ACTION_WATCHDOG = "com.tailscale.ipn.VEHICLE_WATCHDOG"
  const val ACTION_HEARTBEAT = "com.tailscale.ipn.VEHICLE_HEARTBEAT"
  const val ACTION_PROBE = "com.tailscale.ipn.STARTUP_PROBE"
  const val EXTRA_REASON = "start_reason"
  private const val PREFS_NAME = "tailscale_startup_probe"
  private const val KEY_TRIGGER_AT_ELAPSED = "trigger_at_elapsed"
  private const val KEY_HEARTBEAT_AT = "heartbeat_at_elapsed"
  private const val JOB_ID = 3147087
  private const val ALARM_REQUEST = 27147
  private const val PROBE_REQUEST = 27148
  private const val HEARTBEAT_REQUEST = 27150
  private const val WATCHDOG_MS = 10L * 60L * 1000L
  private const val HEARTBEAT_MS = 60_000L
  private const val PROBE_DELAY_MS = 5_000L
  private const val PROBE_WINDOW_MS = 60_000L
  private const val COALESCE_MS = 900L

  @Volatile private var screenRegistered = false
  private val probeArmed = AtomicBoolean(false)
  private var lastStartElapsed = Long.MIN_VALUE

  fun attach(context: Context) {
    val app = context.applicationContext
    registerScreen(app)
    AdbRelay.start()
    wake(app, "application")
  }

  fun wake(context: Context, reason: String) {
    val app = context.applicationContext
    Log.i(TAG, "wake reason=$reason")
    val intent =
        Intent(app, VehicleKeepAliveService::class.java).putExtra(EXTRA_REASON, reason)
    try {
      app.startForegroundService(intent)
    } catch (e: Exception) {
      Log.e(TAG, "keepalive service start failed reason=$reason: $e")
      startIfNeeded(app, reason)
    }
  }

  fun armForeground(context: Context, delayMs: Long) {
    val delay = delayMs.coerceAtLeast(1000L)
    cancelLegacyBroadcast(context)
    val intent =
        Intent(context, VehicleKeepAliveService::class.java).apply {
          action = ACTION_WATCHDOG
          putExtra(EXTRA_REASON, "watchdog_alarm")
        }
    val pending =
        PendingIntent.getForegroundService(
            context,
            ALARM_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    try {
      alarms.setAndAllowWhileIdle(
          AlarmManager.ELAPSED_REALTIME_WAKEUP,
          SystemClock.elapsedRealtime() + delay,
          pending,
      )
      Log.i(TAG, "watchdog armed +${delay}ms")
    } catch (e: Exception) {
      Log.e(TAG, "watchdog arm failed: $e")
    }
  }

  fun ensureScheduled(context: Context) {
    ensureJob(context)
    ensureWatchdog(context)
    ensureHeartbeat(context)
    if (!probeArmed.compareAndSet(false, true)) {
      return
    }
    val now = SystemClock.elapsedRealtime()
    val remaining = keepPrefs(context).getLong(KEY_TRIGGER_AT_ELAPSED, 0L) - now
    if (remaining < 1L || remaining >= PROBE_WINDOW_MS + 1L) {
      scheduleProbe(context, now)
    }
  }

  fun ensureJob(context: Context) {
    try {
      val scheduler =
          context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
      if (scheduler.getPendingJob(JOB_ID) != null) {
        return
      }
      val job =
          JobInfo.Builder(JOB_ID, ComponentName(context, VehicleKeepAliveJob::class.java))
              .setPersisted(true)
              .setPeriodic(JobInfo.getMinPeriodMillis())
              .build()
      val result = scheduler.schedule(job)
      Log.i(TAG, "persistent job schedule result=$result")
    } catch (e: Exception) {
      Log.e(TAG, "persistent job scheduling failed: $e")
    }
  }

  fun ensureWatchdog(context: Context) {
    if (foregroundPending(context, ALARM_REQUEST, ACTION_WATCHDOG, "watchdog_alarm", PendingIntent.FLAG_NO_CREATE) != null) {
      return
    }
    armForeground(context, WATCHDOG_MS)
  }

  fun ensureHeartbeat(context: Context) {
    val now = SystemClock.elapsedRealtime()
    val triggerAt = keepPrefs(context).getLong(KEY_HEARTBEAT_AT, 0L)
    // elapsedRealtime() restarts at 0 after reboot. A stored trigger further ahead than one
    // heartbeat belongs to the previous boot and must not suppress a new alarm.
    val pendingThisBoot = triggerAt in (now + 1_000L)..(now + HEARTBEAT_MS + 10_000L)
    if (pendingThisBoot) {
      return
    }
    if (triggerAt > now + HEARTBEAT_MS + 10_000L) {
      Log.i(TAG, "heartbeat stamp is from a previous boot; rearm")
    }
    armHeartbeat(context)
  }

  fun clearProbe(context: Context) {
    val pending =
        foregroundPending(
            context,
            PROBE_REQUEST,
            ACTION_PROBE,
            "startup_probe",
            PendingIntent.FLAG_NO_CREATE,
        )
    if (pending != null) {
      alarms(context)?.cancel(pending)
      pending.cancel()
    }
    keepPrefs(context).edit().remove(KEY_TRIGGER_AT_ELAPSED).commit()
  }

  fun startIfNeeded(context: Context, reason: String) {
    val app =
        try {
          UninitializedApp.get()
        } catch (_: UninitializedPropertyAccessException) {
          Log.i(TAG, "app not ready reason=$reason")
          return
        }
    if (!app.isAbleToStartVPN() || VpnService.prepare(app) != null) {
      Log.i(TAG, "vpn not ready reason=$reason")
      return
    }
    if (!shouldStartTunnel(context)) {
      Log.i(TAG, "tunnel healthy reason=$reason state=${Notifier.state.value}")
      return
    }
    val now = SystemClock.elapsedRealtime()
    if (lastStartElapsed != Long.MIN_VALUE && now - lastStartElapsed < COALESCE_MS) {
      Log.i(TAG, "start coalesced reason=$reason")
      return
    }
    lastStartElapsed = now
    Log.i(TAG, "startVPN reason=$reason state=${Notifier.state.value}")
    app.startVPN()
  }

  private fun shouldStartTunnel(context: Context): Boolean {
    val want = Notifier.prefs.value?.WantRunning
    if (want == false) {
      return false
    }
    val state = Notifier.state.value
    if (state == Ipn.State.Running || state == Ipn.State.Starting) {
      return false
    }
    if (state == Ipn.State.NeedsLogin || state == Ipn.State.NeedsMachineAuth) {
      return false
    }
    if ((state == Ipn.State.Stopped || state == Ipn.State.Stopping) && want != true) {
      return false
    }
    if (state == Ipn.State.NoState && vpnUp(context)) {
      return false
    }
    return true
  }

  private fun registerScreen(context: Context) {
    if (screenRegistered) {
      return
    }
    val filter =
        IntentFilter().apply {
          addAction(Intent.ACTION_SCREEN_ON)
          addAction(Intent.ACTION_USER_PRESENT)
          addAction(Intent.ACTION_SCREEN_OFF)
        }
    if (Build.VERSION.SDK_INT >= 33) {
      context.registerReceiver(VehicleStartupReceiver(), filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      context.registerReceiver(VehicleStartupReceiver(), filter)
    }
    screenRegistered = true
    Log.i(TAG, "dynamic screen receiver registered")
  }

  private fun scheduleProbe(context: Context, now: Long) {
    try {
      val triggerAt = now + PROBE_DELAY_MS
      val pending =
          PendingIntent.getForegroundService(
              context,
              PROBE_REQUEST,
              Intent(context, VehicleKeepAliveService::class.java).apply {
                action = ACTION_PROBE
                putExtra(EXTRA_REASON, "startup_probe")
              },
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
      val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
      alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
      keepPrefs(context).edit().putLong(KEY_TRIGGER_AT_ELAPSED, triggerAt).commit()
      Log.i(TAG, "startup probe armed +${PROBE_DELAY_MS}ms")
    } catch (e: Exception) {
      Log.e(TAG, "startup probe scheduling failed: $e")
    }
  }

  private fun armHeartbeat(context: Context) {
    val pending =
        foregroundPending(
            context,
            HEARTBEAT_REQUEST,
            ACTION_HEARTBEAT,
            "heartbeat",
            PendingIntent.FLAG_UPDATE_CURRENT,
        ) ?: return
    val triggerAt = SystemClock.elapsedRealtime() + HEARTBEAT_MS
    val alarm = alarms(context) ?: return
    try {
      if (Build.VERSION.SDK_INT >= 31 && alarm.canScheduleExactAlarms()) {
        alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        Log.i(TAG, "heartbeat exact +${HEARTBEAT_MS}ms")
      } else {
        alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        Log.i(TAG, "heartbeat inexact +${HEARTBEAT_MS}ms")
      }
      keepPrefs(context).edit().putLong(KEY_HEARTBEAT_AT, triggerAt).commit()
    } catch (e: SecurityException) {
      alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
      keepPrefs(context).edit().putLong(KEY_HEARTBEAT_AT, triggerAt).commit()
      Log.i(TAG, "heartbeat exact denied; inexact +${HEARTBEAT_MS}ms")
    } catch (e: Exception) {
      Log.e(TAG, "heartbeat arm failed: $e")
    }
  }

  private fun foregroundPending(
      context: Context,
      request: Int,
      action: String,
      reason: String,
      flags: Int,
  ): PendingIntent? {
    val intent =
        Intent(context, VehicleKeepAliveService::class.java).apply {
          this.action = action
          putExtra(EXTRA_REASON, reason)
        }
    return PendingIntent.getForegroundService(
        context,
        request,
        intent,
        flags or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun alarms(context: Context): AlarmManager? {
    return context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
  }

  private fun cancelLegacyBroadcast(context: Context) {
    val legacy =
        Intent(context, VehicleStartupReceiver::class.java).apply { action = ACTION_WATCHDOG }
    val pending =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            legacy,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    alarms?.cancel(pending)
    pending.cancel()
    Log.i(TAG, "legacy broadcast watchdog cancelled")
  }

  private fun keepPrefs(context: Context) =
      context.applicationContext
          .createDeviceProtectedStorageContext()
          .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private fun vpnUp(context: Context): Boolean {
    val cm =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    return cm.allNetworks.any { network ->
      cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
  }
}

class VehicleStartupReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action
    if (action == Intent.ACTION_SCREEN_OFF) {
      Log.i("VehicleKeepAlive", "screen off; leave watchdog armed")
      return
    }
    VehicleKeepAlive.wake(context, action ?: "receiver")
  }
}

class VehicleKeepAliveJob : JobService() {
  override fun onStartJob(params: JobParameters): Boolean {
    VehicleKeepAlive.wake(this, "persistent_job")
    return false
  }

  override fun onStopJob(params: JobParameters): Boolean = true
}

class VehicleKeepAliveProvider : ContentProvider() {
  override fun onCreate(): Boolean {
    // Do not call startForegroundService here. Provider.onCreate runs before
    // Application.onCreate, and that starts the foreground-service timer while the
    // process is still loading. Cold boot then dies with
    // ForegroundServiceDidNotStartInTimeException. Application.onCreate wakes the service
    // after init, and an explicit startProc call still wakes it.
    return true
  }

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
    if (method != "startProc") {
      return super.call(method, arg, extras)
    }
    val reason = extras?.getString("reason") ?: "vendor_start_proc"
    context?.let { VehicleKeepAlive.wake(it, reason) }
    return Bundle().apply { putBoolean("started", true) }
  }

  override fun query(
      uri: Uri,
      projection: Array<out String>?,
      selection: String?,
      selectionArgs: Array<out String>?,
      sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
      uri: Uri,
      values: ContentValues?,
      selection: String?,
      selectionArgs: Array<out String>?,
  ): Int = 0
}

class VehicleKeepAliveService : Service() {
  private val health =
      object : Runnable {
        override fun run() {
          VehicleKeepAlive.ensureJob(this@VehicleKeepAliveService)
          VehicleKeepAlive.ensureWatchdog(this@VehicleKeepAliveService)
          VehicleKeepAlive.ensureHeartbeat(this@VehicleKeepAliveService)
          VehicleKeepAlive.startIfNeeded(this@VehicleKeepAliveService, "monitor_health")
          handler.postDelayed(this, HEALTH_PERIOD_MS)
        }
      }

  override fun onCreate() {
    super.onCreate()
    promote("service_create")
    handler.post(health)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val reason = intent?.getStringExtra(VehicleKeepAlive.EXTRA_REASON) ?: intent?.action ?: "sticky"
    promote(reason)
    handler.post { finishStart(reason, intent?.action) }
    return START_STICKY
  }

  private fun finishStart(reason: String, action: String?) {
    if (reason == "startup_probe" || action == VehicleKeepAlive.ACTION_PROBE) {
      VehicleKeepAlive.clearProbe(this)
    }
    VehicleKeepAlive.ensureScheduled(this)
    VehicleKeepAlive.armForeground(this, WATCHDOG_STEADY_MS)
    VehicleKeepAlive.startIfNeeded(this, reason)
  }

  override fun onDestroy() {
    handler.removeCallbacks(health)
    Log.i(TAG, "service destroy; rearm 5s")
    VehicleKeepAlive.armForeground(this, WATCHDOG_DESTROY_MS)
    super.onDestroy()
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    Log.i(TAG, "task removed; rearm 2s")
    VehicleKeepAlive.armForeground(this, WATCHDOG_TASK_REMOVED_MS)
    super.onTaskRemoved(rootIntent)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun promote(reason: String) {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
      manager?.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "Tailscale keepalive", NotificationManager.IMPORTANCE_MIN),
      )
    }
    val notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tailscale keepalive")
            .setContentText(reason)
            .setOngoing(true)
            .build()
    startForeground(NOTIFICATION_ID, notification)
  }

  private companion object {
    const val TAG = "VehicleKeepAlive"
    const val CHANNEL_ID = "tailscale-keepalive"
    const val NOTIFICATION_ID = 27149
    const val HEALTH_PERIOD_MS = 5_000L
    const val WATCHDOG_STEADY_MS = 600_000L
    const val WATCHDOG_DESTROY_MS = 5_000L
    const val WATCHDOG_TASK_REMOVED_MS = 2_000L
    val handler = Handler(Looper.getMainLooper())
  }
}

class VehicleStartupBridgeService : NotificationListenerService() {
  override fun onCreate() {
    super.onCreate()
    VehicleKeepAlive.wake(this, "system_startup_bridge_create")
  }

  override fun onListenerConnected() {
    super.onListenerConnected()
    VehicleKeepAlive.wake(this, "system_startup_bridge_connected")
  }

  override fun onListenerDisconnected() {
    super.onListenerDisconnected()
    requestRebind(ComponentName(this, VehicleStartupBridgeService::class.java))
  }
}
