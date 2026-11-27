package com.dror.sensorrx

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/** A sensor seen during a scan. */
data class FoundDevice(val address: String, val name: String, val kind: String)

/**
 * Bluetooth LE side: scans for Heart Rate (0x180D) and Cycling Speed & Cadence
 * (0x1816) sensors, connects, subscribes and publishes into [SensorHub].
 * All callers must already hold the Bluetooth permissions.
 */
@SuppressLint("MissingPermission")
class BleSensorManager(context: Context) {

    private val app = context.applicationContext
    private val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("ble", Context.MODE_PRIVATE)

    private class Conn(val address: String, var name: String) {
        var gatt: BluetoothGatt? = null
        val csc = CscCalculator()
        var wanted = true
    }

    private val conns = java.util.concurrent.ConcurrentHashMap<String, Conn>()
    private var scanCallback: ScanCallback? = null

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    // ---------------------------------------------------------------- scanning

    fun startScan(onFound: (FoundDevice) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        stopScan()
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(CSC_SERVICE)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
                val kind = when {
                    HR_SERVICE in uuids -> "Heart rate"
                    CSC_SERVICE in uuids -> "Speed / cadence"
                    else -> "Sensor"
                }
                val name = result.scanRecord?.deviceName ?: result.device.name ?: result.device.address
                main.post { onFound(FoundDevice(result.device.address, name, kind)) }
            }

            override fun onScanFailed(errorCode: Int) {
                SensorHub.setStatus("ble-scan", "BLE scan failed ($errorCode)")
            }
        }
        scanCallback = cb
        scanner.startScan(filters, settings, cb)
        main.postDelayed({ stopScan() }, 20_000)
    }

    fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (e: Exception) { Log.w(TAG, "stopScan", e) }
    }

    // ------------------------------------------------------------- connections

    fun connect(address: String, name: String) {
        if (conns.containsKey(address)) return
        val conn = Conn(address, name)
        conns[address] = conn
        saveAddresses()
        openGatt(conn, autoConnect = false)
    }

    /** Reconnects to the sensors used last time. */
    fun reconnectSaved() {
        val saved = prefs.getStringSet(KEY_ADDRESSES, emptySet()).orEmpty()
        for (entry in saved) {
            val parts = entry.split('|', limit = 2)
            val address = parts[0]
            val name = if (parts.size > 1) parts[1] else address
            if (!conns.containsKey(address)) {
                val conn = Conn(address, name)
                conns[address] = conn
                openGatt(conn, autoConnect = true)
            }
        }
    }

    fun disconnectAll() {
        for (conn in conns.values) {
            conn.wanted = false
            conn.gatt?.disconnect()
            conn.gatt?.close()
            SensorHub.setStatus("ble-${conn.address}", null)
        }
        conns.clear()
        saveAddresses()
    }

    fun connectedCount(): Int = conns.size

    private fun saveAddresses() {
        prefs.edit().putStringSet(KEY_ADDRESSES, conns.values.map { "${it.address}|${it.name}" }.toSet()).apply()
    }

    private fun openGatt(conn: Conn, autoConnect: Boolean) {
        val device: BluetoothDevice = adapter?.getRemoteDevice(conn.address) ?: return
        SensorHub.setStatus("ble-${conn.address}", "BT  ${conn.name}: connecting…")
        conn.gatt = device.connectGatt(app, autoConnect, GattCallback(conn), BluetoothDevice.TRANSPORT_LE)
    }

    private inner class GattCallback(private val conn: Conn) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                SensorHub.setStatus("ble-${conn.address}", "BT  ${conn.name}: discovering…")
                main.post { gatt.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                conn.csc.reset()
                if (conn.wanted && conns[conn.address] === conn) {
                    SensorHub.setStatus("ble-${conn.address}", "BT  ${conn.name}: lost, reconnecting…")
                    // autoConnect=true lets Android reconnect when the sensor wakes up.
                    main.postDelayed({ if (conn.wanted) openGatt(conn, autoConnect = true) }, 1_000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val hr = gatt.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            val csc = gatt.getService(CSC_SERVICE)?.getCharacteristic(CSC_MEASUREMENT)
            val ch = hr ?: csc
            if (ch == null) {
                SensorHub.setStatus("ble-${conn.address}", "BT  ${conn.name}: no HR/CSC service")
                return
            }
            enableNotify(gatt, ch)
            val kind = if (ch === hr) "heart rate" else "speed/cadence"
            SensorHub.setStatus("ble-${conn.address}", "BT  ${conn.name}: connected ($kind)")
        }

        // Android 13+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) = handle(characteristic.uuid, value)

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handle(characteristic.uuid, characteristic.value ?: return)
            }
        }

        private fun handle(uuid: UUID, v: ByteArray) {
            when (uuid) {
                HR_MEASUREMENT -> parseHeartRate(v)?.let {
                    SensorHub.publish(Metric.HEART_RATE, Reading(it.toDouble(), Link.BLE, conn.name))
                }
                CSC_MEASUREMENT -> parseCsc(v)
            }
        }

        private fun parseCsc(v: ByteArray) {
            if (v.isEmpty()) return
            val flags = v[0].toInt()
            var i = 1
            if (flags and 0x01 != 0 && v.size >= i + 6) {
                val revs = u32(v, i); val t = u16(v, i + 4); i += 6
                val w = conn.csc.onWheel(revs, t, SensorHub.wheelCircumferenceMm)
                w.speedKmh?.let { SensorHub.publish(Metric.SPEED, Reading(it, Link.BLE, conn.name)) }
                SensorHub.addDistance(w.distanceM)
            }
            if (flags and 0x02 != 0 && v.size >= i + 4) {
                val revs = u16(v, i); val t = u16(v, i + 2)
                conn.csc.onCrank(revs, t)?.let {
                    SensorHub.publish(Metric.CADENCE, Reading(it, Link.BLE, conn.name))
                }
            }
        }
    }

    private fun enableNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD) ?: return
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    companion object {
        private const val TAG = "BleSensorManager"
        private const val KEY_ADDRESSES = "addresses"

        private fun uuid16(x: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", x))

        val HR_SERVICE: UUID = uuid16(0x180D)
        val HR_MEASUREMENT: UUID = uuid16(0x2A37)
        val CSC_SERVICE: UUID = uuid16(0x1816)
        val CSC_MEASUREMENT: UUID = uuid16(0x2A5B)
        val CCCD: UUID = uuid16(0x2902)

        fun u16(v: ByteArray, i: Int): Int =
            (v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8)

        fun u32(v: ByteArray, i: Int): Long =
            (u16(v, i).toLong()) or (u16(v, i + 2).toLong() shl 16)

        /** Heart Rate Measurement: flag bit 0 selects uint8 or uint16 bpm. */
        fun parseHeartRate(v: ByteArray): Int? {
            if (v.size < 2) return null
            val flags = v[0].toInt()
            return if (flags and 0x01 == 0) v[1].toInt() and 0xFF
            else if (v.size >= 3) u16(v, 1) else null
        }
    }
}
