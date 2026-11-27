package com.dror.sensorrx

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikeCadencePcc
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikeSpeedDistancePcc
import com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import java.math.BigDecimal
import java.util.EnumSet

/**
 * ANT+ side, through Garmin's ANT+ Plugins service. Each connect call opens the
 * plugin's own device picker, so you choose the exact sensor.
 * Needs "ANT Radio Service" and "ANT+ Plugins Service" installed on the phone.
 */
class AntSensorManager(
    /** Called on the main thread when the ANT+ services are missing: (name, packageName). */
    private val onMissingDependency: (String, String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val handles = java.util.concurrent.ConcurrentHashMap<String, PccReleaseHandle<*>>()
    private var lastAntDistance = -1.0

    fun connectHeartRate(activity: Activity) {
        close(KEY_HR)
        handles[KEY_HR] = AntPlusHeartRatePcc.requestAccess(
            activity, activity,
            AntPluginPcc.IPluginAccessResultReceiver<AntPlusHeartRatePcc> { pcc, result, state ->
                if (!checkResult(KEY_HR, "Heart rate", result, state, pcc)) return@IPluginAccessResultReceiver
                val name = pcc.deviceName
                pcc.subscribeHeartRateDataEvent { _, _, computedHeartRate, _, _, _ ->
                    SensorHub.publish(Metric.HEART_RATE, Reading(computedHeartRate.toDouble(), Link.ANT, name))
                }
            },
            stateReceiver(KEY_HR, "Heart rate"),
        )
    }

    fun connectSpeed(activity: Activity) {
        close(KEY_SPEED)
        handles[KEY_SPEED] = AntPlusBikeSpeedDistancePcc.requestAccess(
            activity, activity,
            AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc> { pcc, result, state ->
                if (!checkResult(KEY_SPEED, "Speed", result, state, pcc)) return@IPluginAccessResultReceiver
                subscribeSpeed(pcc)
                // A combined speed+cadence sensor also needs the cadence half.
                if (pcc.isSpeedAndCadenceCombinedSensor) connectComboCadence(activity, pcc.antDeviceNumber)
            },
            stateReceiver(KEY_SPEED, "Speed"),
        )
    }

    fun connectCadence(activity: Activity) {
        close(KEY_CAD)
        handles[KEY_CAD] = AntPlusBikeCadencePcc.requestAccess(
            activity, activity,
            AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeCadencePcc> { pcc, result, state ->
                if (!checkResult(KEY_CAD, "Cadence", result, state, pcc)) return@IPluginAccessResultReceiver
                subscribeCadence(pcc)
            },
            stateReceiver(KEY_CAD, "Cadence"),
        )
    }

    private fun connectComboCadence(activity: Activity, deviceNumber: Int) {
        close(KEY_CAD)
        handles[KEY_CAD] = AntPlusBikeCadencePcc.requestAccess(
            activity, deviceNumber, 0, true,
            AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeCadencePcc> { pcc, result, state ->
                if (!checkResult(KEY_CAD, "Cadence", result, state, pcc)) return@IPluginAccessResultReceiver
                subscribeCadence(pcc)
            },
            stateReceiver(KEY_CAD, "Cadence"),
        )
    }

    private fun subscribeSpeed(pcc: AntPlusBikeSpeedDistancePcc) {
        val name = pcc.deviceName
        val circumferenceM = BigDecimal(SensorHub.wheelCircumferenceMm).movePointLeft(3)
        pcc.subscribeCalculatedSpeedEvent(object : AntPlusBikeSpeedDistancePcc.CalculatedSpeedReceiver(circumferenceM) {
            override fun onNewCalculatedSpeed(t: Long, f: EnumSet<EventFlag>, speedMps: BigDecimal) {
                SensorHub.publish(Metric.SPEED, Reading(speedMps.toDouble() * 3.6, Link.ANT, name))
            }
        })
        lastAntDistance = -1.0
        pcc.subscribeCalculatedAccumulatedDistanceEvent(
            object : AntPlusBikeSpeedDistancePcc.CalculatedAccumulatedDistanceReceiver(circumferenceM) {
                override fun onNewCalculatedAccumulatedDistance(t: Long, f: EnumSet<EventFlag>, meters: BigDecimal) {
                    val m = meters.toDouble()
                    if (lastAntDistance >= 0) SensorHub.addDistance(m - lastAntDistance)
                    lastAntDistance = m
                }
            })
    }

    private fun subscribeCadence(pcc: AntPlusBikeCadencePcc) {
        val name = pcc.deviceName
        pcc.subscribeCalculatedCadenceEvent { _, _, rpm ->
            SensorHub.publish(Metric.CADENCE, Reading(rpm.toDouble(), Link.ANT, name))
        }
    }

    /** @return true when access was granted. */
    private fun checkResult(
        key: String, label: String, result: RequestAccessResult, state: DeviceState?, pcc: AntPluginPcc?,
    ): Boolean {
        val msg = when (result) {
            RequestAccessResult.SUCCESS -> {
                SensorHub.setStatus("ant-$key", "ANT+ $label: ${pcc?.deviceName} ($state)")
                return pcc != null
            }
            RequestAccessResult.USER_CANCELLED -> null
            RequestAccessResult.ADAPTER_NOT_DETECTED -> "no ANT radio found on this phone"
            RequestAccessResult.CHANNEL_NOT_AVAILABLE -> "no free ANT channel"
            RequestAccessResult.DEPENDENCY_NOT_INSTALLED -> {
                val depName = AntPluginPcc.getMissingDependencyName()
                val depPkg = AntPluginPcc.getMissingDependencyPackageName()
                main.post { onMissingDependency(depName, depPkg) }
                "missing $depName"
            }
            else -> "failed ($result)"
        }
        handles.remove(key)
        SensorHub.setStatus("ant-$key", msg?.let { "ANT+ $label: $it" })
        return false
    }

    private fun stateReceiver(key: String, label: String) =
        AntPluginPcc.IDeviceStateChangeReceiver { state ->
            if (state == DeviceState.DEAD) {
                SensorHub.setStatus("ant-$key", "ANT+ $label: connection lost, tap to reconnect")
                handles.remove(key)
            } else {
                SensorHub.setStatus("ant-$key", "ANT+ $label: $state")
            }
        }

    private fun close(key: String) {
        handles.remove(key)?.close()
        SensorHub.setStatus("ant-$key", null)
    }

    fun disconnectAll() {
        listOf(KEY_HR, KEY_SPEED, KEY_CAD).forEach { close(it) }
    }

    fun connectedCount(): Int = handles.size

    private companion object {
        const val KEY_HR = "hr"
        const val KEY_SPEED = "speed"
        const val KEY_CAD = "cadence"
    }
}
