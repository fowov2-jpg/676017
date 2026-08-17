#!/usr/bin/env bash
set -euo pipefail

artifact_dir=${1:?artifact directory is required}
api_level=${2:-35}
package_name='app.humanrouter'
test_runner='app.humanrouter.test/androidx.test.runner.AndroidJUnitRunner'
test_class='app.humanrouter.GpsRouteReplayInstrumentationTest'
app_apk=$(find "$artifact_dir" -type f -name 'app-debug.apk' -print -quit)
test_apk=$(find "$artifact_dir" -type f -name 'app-debug-androidTest.apk' -print -quit)
output="android/emulator-artifacts/gps-route-replay-api-${api_level}"
remote_dir="/sdcard/Android/data/${package_name}/files/gps-replay"

mkdir -p "$output"
test -s "$app_apk"
test -s "$test_apk"

adb wait-for-device
adb shell settings put global hide_error_dialogs 1
adb shell settings put global anr_show_background 0 >/dev/null 2>&1 || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r "$app_apk"
adb install -r "$test_apk"

instrumentation_output="$output/instrumentation.txt"
set +e
adb shell am instrument -w -r -e class "$test_class" "$test_runner" | tee "$instrumentation_output"
status=${PIPESTATUS[0]}
set -e

# Pull visual evidence even after a failed assertion so transfer regressions remain inspectable.
adb pull "$remote_dir" "$output/gps-replay" >/dev/null 2>&1 || true

if (( status != 0 )); then
  exit "$status"
fi
grep -F 'OK (1 test)' "$instrumentation_output"
if grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$instrumentation_output"; then
  exit 1
fi

for name in \
  gps-03-bus-wait.png \
  gps-04-bus-onboard.png \
  gps-05-bus-exit.png \
  gps-06-transfer.png \
  gps-07-metro-wait.png \
  gps-08-metro-onboard.png \
  gps-09-metro-exit.png \
  gps-11-finish.png; do
  test -s "$output/gps-replay/$name"
done

# Persist environment metadata beside screenshots for later visual audits.
{
  echo "api_level=$api_level"
  adb shell wm size | tr -d '\r'
  adb shell wm density | tr -d '\r'
  adb shell settings get system font_scale | tr -d '\r' | sed 's/^/font_scale=/'
} > "$output/environment.txt"

echo "GPS route replay passed: approach -> bus -> transfer -> metro -> finish"
