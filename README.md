# Sensor Receiver

Android app that reads **heart rate, speed and cadence** from sensors over both **ANT+** and **Bluetooth LE**, shows them live and logs them to CSV.

Tested sensor targets: Garmin HRM 600 (HR), Magene speed and cadence sensors (dual ANT+/BLE).

## Features
- Bluetooth LE: scans for standard Heart Rate (0x180D) and Cycling Speed & Cadence (0x1816) sensors, connects to any number at once, and reconnects automatically (also on next app start).
- ANT+: Heart Rate, Bike Speed, Bike Cadence (and combined speed+cadence) through Garmin's ANT+ Plugins service. Each button opens the plugin's own sensor picker.
- Big live display of HR / speed / cadence with the source (ANT+ or BT and sensor name), plus distance.
- Recording runs as a foreground service (keeps going with the screen off) and writes one row per second:
  `time, elapsed_s, heart_rate_bpm, speed_kmh, cadence_rpm, distance_m, hr_source, speed_source, cadence_source`
- Logs are saved in `Android/data/com.dror.sensorrx/files/logs/` and can be shared from the app.
- GPS speed runs alongside the sensors: the speed tile uses the wheel sensor when it is sending and switches to GPS automatically when it is not (distance too). The log has both speeds plus position.
- Adjustable wheel circumference (default 2105 mm).

## Build
**GitHub:** push this folder to a repo. The workflow in `.github/workflows/build-apk.yml` builds on every push; download the APK from the run's *Artifacts* (`SensorReceiver-apk`). You can also start it by hand from the Actions tab (*Run workflow*).

**Android Studio:** open the folder and run. The ANT+ library (`antpluginlib_3-9-0.aar`) is downloaded automatically into `app/libs/` on the first build from the official [ANT-Android-SDKs](https://github.com/ant-wireless/ANT-Android-SDKs) repo.

## On the phone
1. Install the APK (allow "install unknown apps").
2. For ANT+, the phone also needs **ANT Radio Service** and **ANT+ Plugins Service** from the Play Store. The app offers to open the store if they're missing.
3. Allow the Bluetooth ("Nearby devices") and notification permissions.
4. Wake the sensors (spin the wheel / crank, wear the strap), then connect.

Use each sensor on **one** link at a time: a sensor connected over Bluetooth often stops broadcasting ANT+ to a second receiver, and vice versa, depending on the model.

## Code map
| File | What it does |
|---|---|
| `SensorHub.kt` | Shared latest values, distance, connection status |
| `BleSensorManager.kt` | BLE scan / connect / notifications / parsing |
| `CscCalculator.kt` | Wheel & crank counters → speed, distance, cadence |
| `AntSensorManager.kt` | ANT+ access through the Garmin plugin library |
| `RecordingService.kt` | Foreground service writing the CSV |
| `MainActivity.kt` | UI and permissions |
