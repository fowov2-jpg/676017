#!/usr/bin/env bash
set -euo pipefail

artifact_dir=${1:?artifact directory is required}
api_level=${2:-35}
package_name='app.humanrouter'
activity_name='app.humanrouter/.MainActivity'
test_runner='app.humanrouter.test/androidx.test.runner.AndroidJUnitRunner'
app_apk=$(find "$artifact_dir" -type f -name 'app-debug.apk' -print -quit)
test_apk=$(find "$artifact_dir" -type f -name 'app-debug-androidTest.apk' -print -quit)
output_root="android/emulator-artifacts/responsive-api-${api_level}"

test -s "$app_apk"
test -s "$test_apk"
mkdir -p "$output_root"

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
  adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

capture_fixture() {
  local out=$1
  local screen=$2
  local name=$3
  local expected=$4
  adb shell am force-stop "$package_name" >/dev/null
  local start_output
  start_output=$(adb shell am start -W -n "$activity_name" --es qa_screen "$screen")
  grep -F 'Status: ok' <<<"$start_output"
  sleep 3
  adb exec-out screencap -p >"$out/${name}.png"
  test -s "$out/${name}.png"
  adb shell uiautomator dump /sdcard/vh-responsive.xml >/dev/null
  adb pull /sdcard/vh-responsive.xml "$out/${name}.xml" >/dev/null
  grep -F "$expected" "$out/${name}.xml"
}

run_viewport() {
  local label=$1
  local size=$2
  local density=$3
  local expected_width_dp=$4
  local expected_height_dp=$5
  local out="$output_root/$label"
  mkdir -p "$out"

  adb shell wm size "$size"
  adb shell wm density "$density"
  adb shell am force-stop "$package_name" >/dev/null
  sleep 2

  {
    printf 'label=%s\n' "$label"
    printf 'requested_size=%s\n' "$size"
    printf 'requested_density=%s\n' "$density"
    printf 'expected_width_dp=%s\n' "$expected_width_dp"
    printf 'expected_height_dp=%s\n' "$expected_height_dp"
    adb shell wm size | tr -d '\r'
    adb shell wm density | tr -d '\r'
  } >"$out/environment.txt"

  instrumentation_output="$out/instrumentation.txt"
  set +e
  adb shell am instrument -w -r "$test_runner" | tee "$instrumentation_output"
  instrumentation_status=${PIPESTATUS[0]}
  set -e
  if (( instrumentation_status != 0 )); then
    exit "$instrumentation_status"
  fi
  grep -E 'OK \([0-9]+ tests?\)' "$instrumentation_output"
  if grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$instrumentation_output"; then
    exit 1
  fi

  capture_fixture "$out" home home 'Куда едем?'
  capture_fixture "$out" routes route-options 'Бабушкинская'
  capture_fixture "$out" trip active-trip 'В пути'
  capture_fixture "$out" settings settings 'Настройки'

  if adb logcat -d -v brief | grep -A 12 'FATAL EXCEPTION' | grep -F "$package_name"; then
    echo "Fatal app exception detected for viewport $label" >&2
    exit 1
  fi
}

# 720/320 = 360dp, 1600/320 = 800dp.
run_viewport compact-phone 720x1600 320 360 800

# 1080/216 = 800dp, 1728/216 = 1280dp: tablet-class width without requiring a
# second emulator hardware profile, so the exact same APK is tested under a tablet window.
run_viewport tablet 1080x1728 216 800 1280

echo "Responsive compact-phone/tablet smoke passed on API $api_level"
