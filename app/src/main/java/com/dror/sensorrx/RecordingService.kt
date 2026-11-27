package com.dror.sensorrx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that writes one CSV row per second while recording, and
 * keeps the app (and its sensor connections) alive with the screen off.
 */
class RecordingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var writer: FileWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedAt = 0L
    private var rows = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (writer != null) return START_STICKY

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Recording…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )

        val dir = logDir(this)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "ride_$stamp.csv")
        writer = FileWriter(file).apply {
            write("time,elapsed_s,heart_rate_bpm,speed_kmh,cadence_rpm,distance_m,hr_source,speed_source,cadence_source\n")
        }
        currentFile = file
        startedAt = SystemClock.elapsedRealtime()
        SensorHub.resetDistance()
        rows = 0

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorRx:recording")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        isRecording = true
        handler.post(tick)
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            writeRow()
            handler.postDelayed(this, 1_000)
        }
    }

    private fun writeRow() {
        val w = writer ?: return
        val hr = SensorHub.current(Metric.HEART_RATE)
        val sp = SensorHub.current(Metric.SPEED)
        val cad = SensorHub.current(Metric.CADENCE)
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        fun num(r: Reading?, digits: Int) = r?.let { String.format(Locale.US, "%.${digits}f", it.value) } ?: ""
        fun src(r: Reading?) = r?.let { "${it.link}:${it.deviceName.replace(',', ' ')}" } ?: ""
        w.write(
            "$time,${String.format(Locale.US, "%.0f", elapsed)},${num(hr, 0)},${num(sp, 1)},${num(cad, 0)}," +
                "${String.format(Locale.US, "%.0f", SensorHub.distanceMeters)},${src(hr)},${src(sp)},${src(cad)}\n",
        )
        rows++
        if (rows % 10 == 0) {
            w.flush()
            val mins = (elapsed / 60).toInt()
            val text = "Recording ${mins} min · HR ${num(hr, 0).ifEmpty { "–" }} · " +
                "${num(sp, 1).ifEmpty { "–" }} km/h · ${num(cad, 0).ifEmpty { "–" }} rpm"
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        writer?.run { flush(); close() }
        writer = null
        wakeLock?.let { if (it.isHeld) it.release() }
        isRecording = false
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("Sensor Receiver")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.dror.sensorrx.STOP"

        @Volatile var isRecording = false
            private set
        @Volatile var currentFile: File? = null
            private set

        fun logDir(context: Context): File =
            File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
    }
}
