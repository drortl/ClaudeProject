package com.dror.sensorrx

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import com.dror.sensorrx.databinding.ActivityMainBinding
import java.util.Locale

/** Keeps the radios alive across screen rotations. */
object Sensors {
    lateinit var ble: BleSensorManager
    lateinit var ant: AntSensorManager
    lateinit var gps: GpsTracker
    var onAntMissing: ((String, String) -> Unit)? = null
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        ble = BleSensorManager(context)
        ant = AntSensorManager { name, pkg -> onAntMissing?.invoke(name, pkg) }
        gps = GpsTracker(context)
        ready = true
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val refresh: () -> Unit = { render() }
    private var didAutoReconnect = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startGpsIfAllowed()
            if (hasBlePermissions() && !didAutoReconnect) {
                didAutoReconnect = true
                Sensors.ble.reconnectSaved()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Sensors.init(this)
        Sensors.onAntMissing = { name, pkg -> askInstall(name, pkg) }

        SensorHub.wheelCircumferenceMm = prefs.getInt("wheel_mm", 2105)
        b.wheelInput.setText(SensorHub.wheelCircumferenceMm.toString())
        b.wheelInput.doAfterTextChanged { text ->
            val mm = text?.toString()?.toIntOrNull()
            if (mm != null && mm in 500..3500) {
                SensorHub.wheelCircumferenceMm = mm
                prefs.edit().putInt("wheel_mm", mm).apply()
            }
        }

        b.btnBleScan.setOnClickListener { scanBle() }
        b.btnAntHr.setOnClickListener { Sensors.ant.connectHeartRate(this) }
        b.btnAntSpeed.setOnClickListener { Sensors.ant.connectSpeed(this) }
        b.btnAntCadence.setOnClickListener { Sensors.ant.connectCadence(this) }
        b.btnDisconnect.setOnClickListener {
            Sensors.ble.disconnectAll()
            Sensors.ant.disconnectAll()
        }
        b.btnRecord.setOnClickListener { toggleRecording() }
        b.btnShare.setOnClickListener { shareLastLog() }
        b.btnResetDistance.setOnClickListener { SensorHub.resetDistance() }

        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        SensorHub.addListener(refresh)
        ticker.run()
    }

    override fun onStop() {
        SensorHub.removeListener(refresh)
        b.root.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        Sensors.onAntMissing = null
        // Only drop the sensors when the user really leaves and nothing is recording.
        if (isFinishing && !RecordingService.isRecording) {
            Sensors.ble.disconnectAll()
            Sensors.ant.disconnectAll()
            Sensors.gps.stop()
        }
        super.onDestroy()
    }

    /** Re-render every second so stale values fade to "--". */
    private val ticker = object : Runnable {
        override fun run() {
            render()
            b.root.postDelayed(this, 1_000)
        }
    }

    private fun render() {
        fun show(metric: Metric, digits: Int, value: android.widget.TextView, source: android.widget.TextView) {
            val r = SensorHub.current(metric)
            value.text = r?.let { String.format(Locale.US, "%.${digits}f", it.value) } ?: "--"
            source.text = r?.let { "${if (it.link == Link.ANT) "ANT+" else "BT"} · ${it.deviceName}" } ?: "no data"
        }
        show(Metric.HEART_RATE, 0, b.hrValue, b.hrSource)
        // Speed: wheel sensor when it is sending, GPS automatically otherwise.
        val speed = SensorHub.bestSpeed()
        b.speedValue.text = speed?.let { String.format(Locale.US, "%.1f", it.value) } ?: "--"
        b.speedSource.text = when {
            speed == null -> "no data"
            speed.link == Link.GPS -> "GPS (no sensor data) · ${speed.deviceName}"
            else -> "${if (speed.link == Link.ANT) "ANT+" else "BT"} · ${speed.deviceName}"
        }
        val gps = SensorHub.current(Metric.GPS_SPEED)
        b.gpsLine.text = when {
            !Sensors.gps.isRunning -> "GPS: off (allow location to enable)"
            gps == null -> "GPS: waiting for fix…"
            else -> String.format(Locale.US, "GPS: %.1f km/h · %s", gps.value, gps.deviceName)
        }
        show(Metric.CADENCE, 0, b.cadenceValue, b.cadenceSource)
        b.distanceValue.text = String.format(Locale.US, "%.2f km", SensorHub.distanceMeters / 1000)
        b.statusText.text = SensorHub.statusText()

        val recording = RecordingService.isRecording
        b.btnRecord.text = if (recording) "■  Stop recording" else "●  Start recording"
        b.recordInfo.text = when {
            recording -> "Logging to ${RecordingService.currentFile?.name}"
            RecordingService.currentFile != null -> "Last log: ${RecordingService.currentFile?.name}"
            else -> "Logs are saved to Android/data/$packageName/files/logs"
        }
    }

    // ------------------------------------------------------------------ BLE

    private fun scanBle() {
        if (!hasBlePermissions()) {
            requestNeededPermissions(); return
        }
        if (!Sensors.ble.isEnabled) {
            Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_LONG).show(); return
        }
        val found = mutableListOf<FoundDevice>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Searching… wake up your sensors")
            .setAdapter(adapter) { _, which ->
                Sensors.ble.stopScan()
                val d = found[which]
                Sensors.ble.connect(d.address, d.name)
            }
            .setNegativeButton("Close", null)
            .setOnDismissListener { Sensors.ble.stopScan() }
            .show()
        Sensors.ble.startScan { d ->
            if (found.none { it.address == d.address }) {
                found += d
                adapter.add("${d.name}\n${d.kind} · ${d.address}")
                adapter.notifyDataSetChanged()
                dialog.setTitle("Tap a sensor to connect")
            }
        }
    }

    private fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBlePermissions() = blePermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startGpsIfAllowed() {
        if (hasLocationPermission()) Sensors.gps.start()
    }

    private fun requestNeededPermissions() {
        startGpsIfAllowed()
        val wanted = blePermissions().toMutableList()
        wanted += Manifest.permission.ACCESS_FINE_LOCATION
        wanted += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.distinct().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            if (!didAutoReconnect) {
                didAutoReconnect = true
                Sensors.ble.reconnectSaved()
            }
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ------------------------------------------------------------- recording

    private fun toggleRecording() {
        val intent = Intent(this, RecordingService::class.java)
        if (RecordingService.isRecording) {
            stopService(intent)
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
        b.root.postDelayed({ render() }, 300)
    }

    private fun shareLastLog() {
        val file = RecordingService.currentFile
            ?: RecordingService.logDir(this).listFiles()?.maxByOrNull { it.lastModified() }
        if (file == null) {
            Toast.makeText(this, "No logs yet", Toast.LENGTH_SHORT).show(); return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share ${file.name}"))
    }

    // ------------------------------------------------------------------- ANT

    private fun askInstall(name: String, pkg: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("ANT+ service missing")
            .setMessage("\"$name\" is needed for ANT+ sensors. Install it from the Play Store?")
            .setPositiveButton("Open store") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
