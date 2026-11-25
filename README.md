# Battery Widget

An Android home screen widget that shows the battery level in percent, in big
digits. It is the size of one app icon (1 x 1 cell) and uses almost no power.

- White = on battery. Green = charger connected. Red = 15% or less.
- Tap the widget to refresh it now.
- You can resize it. The digits grow with it (TextView autosize).
- Android 8.0 (API 26) or newer. No permissions. APK size is about 17 KB.

## How it saves power

| What | How |
|---|---|
| No wake-ups | `updatePeriodMillis="0"`, so the system never wakes the phone for this widget. The refresh alarm is `ELAPSED_REALTIME` (non-wakeup). |
| Fresh when you look | If a refresh comes due while the phone sleeps, Android delivers it the next time the phone wakes up (for example, screen on). |
| Refresh interval | 15 min on battery, 2 min on the charger. Set in `WidgetUpdater.java`. |
| Each refresh | One broadcast: read the sticky `ACTION_BATTERY_CHANGED` intent, push one `RemoteViews`, plan the next alarm. Then the process can be killed. |
| No background work | No service, no wake lock, no job, no dynamic receiver outside the open info screen. Plain Java, no libraries, so the process starts fast. |

The widget also refreshes when it is added, when you tap it, after a reboot,
after an app update, and when you leave the info screen.

### Known limit: charger plug-in is not instant

After you connect or remove the charger, the color changes at the next
refresh (up to about 15 min). Tap the widget to see it at once. There is no
cheap way to react at once:

- `ACTION_POWER_CONNECTED` is not delivered to manifest receivers for apps
  that target Android 8+. Listening for it needs a process that stays alive
  (a foreground service with a notification), which costs power.
- A JobScheduler job with `setRequiresCharging(true)` was tested on the
  emulator. Android counts the phone as "charging" for jobs only after the
  level rises and then `battery_charged_delay_ms` (900000 ms = 15 min)
  passes. It was never faster than the normal refresh, so it was removed.

## Files

```
app/src/main/java/com/drortl/batterywidget/
  BatteryWidgetProvider.java  widget broadcasts -> WidgetUpdater
  WidgetUpdater.java          draw the widget, plan the next refresh (power rules here)
  BatteryState.java           read level and charger state
  MainActivity.java           info screen + "Add widget to home screen" button
app/src/main/res/
  xml/battery_widget_info.xml widget size (1x1), resizable, updatePeriodMillis=0
  layout/widget_battery.xml   widget layout (autosize text)
  values/styles.xml           shared widget text style
  values/colors.xml           text and background colors
```

## Build

Windows (this PC already has JDK 17 and the Android SDK; `ANDROID_HOME` is set):

```
gradlew.bat assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. The release build
is minified and signed with the local debug key, so you can install it
directly. Use `assembleDebug` for a debug build.

Linux (CI, WSL): run `./setup.sh` once to install JDK 17, the Android SDK and
Gradle, then `source ~/android-dev/env.sh` and `./gradlew assembleRelease`.

Versions: Gradle 8.7 (wrapper), Android Gradle Plugin 8.5.2, compileSdk and
targetSdk 34, minSdk 26.

## Install

With the phone connected over USB (USB debugging on):

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the APK to the phone and open it.

Then open **Battery Widget** once and tap **Add widget to home screen**, or
long-press the home screen, tap **Widgets**, and drag **Battery %** to a free
spot.

## Note for some phones

Some phone makers (for example Xiaomi, Huawei, Samsung with strong battery
saving) stop background work of apps more strictly than stock Android. If the
number stops updating on such a phone, set Battery Widget to "Unrestricted" /
"No restrictions" in the app's battery settings. This does not make it use
more power; it only lets the planned refresh run.
