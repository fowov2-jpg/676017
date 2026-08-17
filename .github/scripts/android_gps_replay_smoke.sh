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

cleanup() {
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
  adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
  adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

required_names=(
  gps-03-bus-wait.png
  gps-04-bus-onboard.png
  gps-05-bus-exit.png
  gps-06-transfer.png
  gps-07-metro-wait.png
  gps-08-metro-onboard.png
  gps-09-metro-exit.png
  gps-11-finish.png
)

verify_evidence() {
  local dir=$1
  for name in "${required_names[@]}"; do
    test -s "$dir/$name"
  done
}

wait_for_remote_evidence() {
  local deadline=$((SECONDS + 8))
  while (( SECONDS < deadline )); do
    local missing=0
    for name in "${required_names[@]}"; do
      if ! adb shell test -s "$remote_dir/$name"; then
        missing=$((missing + 1))
      fi
    done
    if (( missing == 0 )); then
      adb shell sync >/dev/null 2>&1 || true
      return 0
    fi
    sleep 0.25
  done
  echo "GPS replay evidence did not fully materialize on device:" >&2
  adb shell ls -l "$remote_dir" >&2 || true
  return 1
}

run_replay() {
  local label=$1
  local size=$2
  local density=$3
  local out="$output/$label"
  mkdir -p "$out"

  adb shell rm -rf "$remote_dir" >/dev/null 2>&1 || true
  adb shell wm size "$size"
  adb shell wm density "$density"
  adb shell settings put system font_scale 1.0
  adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
  sleep 2

  local instrumentation_output="$out/instrumentation.txt"
  set +e
  adb shell am instrument -w -r -e class "$test_class" "$test_runner" | tee "$instrumentation_output"
  local status=${PIPESTATUS[0]}
  set -e

  if (( status == 0 )); then
    wait_for_remote_evidence
  fi

  # Pull visual evidence even after a failed assertion so transfer regressions remain inspectable.
  adb pull "$remote_dir" "$out/gps-replay" >/dev/null 2>&1 || true

  {
    echo "label=$label"
    echo "api_level=$api_level"
    echo "requested_size=$size"
    echo "requested_density=$density"
    adb shell wm size | tr -d '\r'
    adb shell wm density | tr -d '\r'
    adb shell settings get system font_scale | tr -d '\r' | sed 's/^/font_scale=/'
  } > "$out/environment.txt"

  if (( status != 0 )); then
    exit "$status"
  fi
  grep -F 'OK (1 test)' "$instrumentation_output"
  if grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$instrumentation_output"; then
    exit 1
  fi
  verify_evidence "$out/gps-replay"
}

# Standard modern phone viewport: ~411 x 914 dp.
run_replay phone 1080x2400 420

# Tablet portrait viewport: 800 x 1280 dp.
run_replay tablet 1080x1728 216

echo "GPS route replay passed on phone and tablet: approach -> bus -> transfer -> metro -> finish"
