package com.dror.sensorrx

import android.os.SystemClock

/**
 * Turns the cumulative counters of the Bluetooth Cycling Speed and Cadence
 * profile (0x2A5B) into speed, distance and cadence. Event times are in
 * 1/1024 s and roll over at 65536; wheel revs are uint32, crank revs uint16.
 */
class CscCalculator {

    data class Wheel(val speedKmh: Double?, val distanceM: Double)

    private var wheelRevs = -1L
    private var wheelTime = 0
    private var wheelChangedAt = 0L

    private var crankRevs = -1
    private var crankTime = 0
    private var crankChangedAt = 0L

    fun reset() {
        wheelRevs = -1; crankRevs = -1
    }

    /** @return speed (null = no new information) and distance travelled since last call. */
    fun onWheel(revs: Long, time1024: Int, circumferenceMm: Int): Wheel {
        val now = SystemClock.elapsedRealtime()
        if (wheelRevs < 0) {
            wheelRevs = revs; wheelTime = time1024; wheelChangedAt = now
            return Wheel(null, 0.0)
        }
        val dRevs = (revs - wheelRevs) and 0xFFFFFFFFL
        val dTime = (time1024 - wheelTime) and 0xFFFF
        if (dRevs > 200) { // nonsense jump (sensor reset), start over
            wheelRevs = revs; wheelTime = time1024; wheelChangedAt = now
            return Wheel(null, 0.0)
        }
        if (dRevs == 0L || dTime == 0) {
            val idle = now - wheelChangedAt > COAST_TO_ZERO_MS
            return Wheel(if (idle) 0.0 else null, 0.0)
        }
        wheelRevs = revs; wheelTime = time1024; wheelChangedAt = now
        val meters = dRevs * circumferenceMm / 1000.0
        val seconds = dTime / 1024.0
        return Wheel(meters / seconds * 3.6, meters)
    }

    /** @return cadence in rpm, or null when there is no new information. */
    fun onCrank(revs: Int, time1024: Int): Double? {
        val now = SystemClock.elapsedRealtime()
        if (crankRevs < 0) {
            crankRevs = revs; crankTime = time1024; crankChangedAt = now
            return null
        }
        val dRevs = (revs - crankRevs) and 0xFFFF
        val dTime = (time1024 - crankTime) and 0xFFFF
        if (dRevs > 50) {
            crankRevs = revs; crankTime = time1024; crankChangedAt = now
            return null
        }
        if (dRevs == 0 || dTime == 0) {
            return if (now - crankChangedAt > COAST_TO_ZERO_MS) 0.0 else null
        }
        crankRevs = revs; crankTime = time1024; crankChangedAt = now
        return dRevs * 60.0 * 1024.0 / dTime
    }

    companion object {
        const val COAST_TO_ZERO_MS = 3_000L
    }
}
