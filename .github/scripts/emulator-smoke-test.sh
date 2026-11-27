#!/usr/bin/env bash
# Reusable Android smoke test for any app.
# Usage: emulator-smoke-test.sh <path/to/app.apk> <applicationId> [seconds-to-run]
#
# Runs on a booted emulator (adb already connected). Checks that the app:
#   1. installs and starts with all permissions granted,
#   2. survives a screen rotation (activity recreated),
#   3. starts again with NO permissions granted (first-run dialogs),
# without a crash (FATAL EXCEPTION) or an ANR. Logcat is saved to smoke-logcat.txt.
set -uo pipefail

APK="$1"
PKG="$2"
RUN_SECS="${3:-15}"
FAILED=0

log()  { echo "::group::$*"; }
done_() { echo "::endgroup::"; }

check_alive() {
  local stage="$1"
  if ! adb shell pidof "$PKG" >/dev/null 2>&1; then
    echo "::error::[$stage] app process is not running"
    FAILED=1
  fi
  if adb logcat -d | grep -E "FATAL EXCEPTION|ANR in $PKG" -A 25 | grep -q .; then
    echo "::error::[$stage] crash or ANR found in logcat:"
    adb logcat -d | grep -E "FATAL EXCEPTION|ANR in $PKG" -A 25 | head -60
    FAILED=1
  fi
}

launch() {
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep "$RUN_SECS"
}

adb wait-for-device
adb shell settings put system accelerometer_rotation 0

log "1. Install with permissions granted and launch"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" || { echo "::error::install failed"; exit 1; }
adb logcat -c
launch
check_alive "launch"
adb exec-out screencap -p > smoke-1-launch.png || true
done_

log "2. Rotate screen (activity recreation)"
adb shell settings put system user_rotation 1; sleep 5
adb shell settings put system user_rotation 0; sleep 5
check_alive "rotation"
done_

log "3. Fresh install without permissions"
adb shell am force-stop "$PKG"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r "$APK" || { echo "::error::install failed"; exit 1; }
adb logcat -c
launch
check_alive "no-permissions"
adb exec-out screencap -p > smoke-3-no-permissions.png || true
done_

adb logcat -d > smoke-logcat.txt || true

if [ "$FAILED" -ne 0 ]; then
  echo "::error::Smoke test FAILED"
  exit 1
fi
echo "Smoke test passed"
