package com.dror.sensorrx

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

enum class Metric { HEART_RATE, SPEED, CADENCE, GPS_SPEED }
enum class Link { ANT, BLE, GPS }

/** Last GPS fix, for the log. */
data class GpsFix(val lat: Double, val lon: Double, val altM: Double?, val accuracyM: Float?)

/** Latest reading of one metric. speed is km/h, cadence rpm, heart rate bpm. */
data class Reading(
    val value: Double,
    val link: Link,
    val deviceName: String,
    val atMillis: Long = SystemClock.elapsedRealtime(),
)

/**
 * Single place where both radios publish their data and where the UI and the
 * recorder read it. Thread-safe; listeners are called on the main thread.
 */
object SensorHub {
    /** A reading older than this is treated as "no data". */
    const val STALE_MS = 5_000L

    private val latest = java.util.concurrent.ConcurrentHashMap<Metric, Reading>()
    @Volatile var distanceMeters: Double = 0.0
        private set
    @Volatile var wheelCircumferenceMm: Int = 2105
    @Volatile var gpsFix: GpsFix? = null
    @Volatile var gpsFixAtMillis: Long = 0L

    /** Speed to show: the wheel sensor when it is sending, otherwise GPS. */
    fun bestSpeed(): Reading? = current(Metric.SPEED) ?: current(Metric.GPS_SPEED)

    /** Fresh GPS position, or null when none arrived within [STALE_MS]. */
    fun currentFix(): GpsFix? =
        if (SystemClock.elapsedRealtime() - gpsFixAtMillis <= STALE_MS) gpsFix else null

    private val statusLines = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var notifyPosted = false

    fun publish(metric: Metric, reading: Reading) {
        latest[metric] = reading
        notifyListeners()
    }

    fun addDistance(meters: Double) {
        if (meters > 0 && meters < 1000) {
            distanceMeters += meters
            notifyListeners()
        }
    }

    fun resetDistance() {
        distanceMeters = 0.0
        notifyListeners()
    }

    /** Fresh reading, or null when nothing arrived within [STALE_MS]. */
    fun current(metric: Metric): Reading? {
        val r = latest[metric] ?: return null
        return if (SystemClock.elapsedRealtime() - r.atMillis <= STALE_MS) r else null
    }

    fun setStatus(key: String, text: String?) {
        if (text == null) statusLines.remove(key) else statusLines[key] = text
        notifyListeners()
    }

    fun statusText(): String =
        statusLines.toSortedMap().values.joinToString("\n").ifEmpty { "No sensors connected" }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun notifyListeners() {
        synchronized(this) {
            if (notifyPosted) return
            notifyPosted = true
        }
        // Coalesce bursts of updates into one UI refresh.
        main.postDelayed({
            synchronized(this) { notifyPosted = false }
            listeners.forEach { it() }
        }, 100)
    }
}
