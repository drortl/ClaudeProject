package com.dror.sensorrx

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

/**
 * Phone GPS as a speed source. Uses the plain Android LocationManager (no Google
 * Play services), one fix per second. Publishes [Metric.GPS_SPEED] in km/h and
 * keeps the last position in [SensorHub.gpsFix].
 */
@SuppressLint("MissingPermission")
class GpsTracker(context: Context) {

    private val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var isRunning = false
        private set

    private var lastForDistance: Location? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // GPS distance counts only while the wheel sensor is silent, so nothing is counted twice.
            val goodFix = loc.hasAccuracy() && loc.accuracy <= 25f
            if (SensorHub.current(Metric.SPEED) == null && goodFix) {
                lastForDistance?.let { prev ->
                    val gapMs = loc.time - prev.time
                    if (gapMs in 1..10_000) SensorHub.addDistance(prev.distanceTo(loc).toDouble())
                }
                lastForDistance = loc
            } else {
                lastForDistance = null
            }
            SensorHub.gpsFix = GpsFix(
                loc.latitude, loc.longitude,
                if (loc.hasAltitude()) loc.altitude else null,
                if (loc.hasAccuracy()) loc.accuracy else null,
            )
            SensorHub.gpsFixAtMillis = SystemClock.elapsedRealtime()
            if (loc.hasSpeed()) {
                val acc = if (loc.hasAccuracy()) "±${loc.accuracy.toInt()} m" else "phone GPS"
                SensorHub.publish(Metric.GPS_SPEED, Reading(loc.speed * 3.6, Link.GPS, acc))
            }
            SensorHub.setStatus("gps", "GPS  fix, ${loc.accuracy.toInt()} m accuracy")
        }

        override fun onProviderDisabled(provider: String) {
            SensorHub.setStatus("gps", "GPS  location is turned off in phone settings")
        }

        override fun onProviderEnabled(provider: String) {
            SensorHub.setStatus("gps", "GPS  waiting for fix…")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** Caller must hold ACCESS_FINE_LOCATION. */
    fun start() {
        if (isRunning) return
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            SensorHub.setStatus("gps", "GPS  location is turned off in phone settings")
        } else {
            SensorHub.setStatus("gps", "GPS  waiting for fix…")
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        lm.removeUpdates(listener)
        isRunning = false
        SensorHub.setStatus("gps", null)
    }
}
