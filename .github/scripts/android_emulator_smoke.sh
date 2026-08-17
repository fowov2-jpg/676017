#!/usr/bin/env bash
set -euo pipefail

artifact_dir=${1:?artifact directory is required}
api_level=${2:?API level is required}
package_name='app.humanrouter'
activity_name='app.humanrouter/.MainActivity'
test_runner='app.humanrouter.test/androidx.test.runner.AndroidJUnitRunner'
app_apk=$(find "$artifact_dir" -type f -name 'app-debug.apk' -print -quit)
test_apk=$(find "$artifact_dir" -type f -name 'app-debug-androidTest.apk' -print -quit)
output_dir="android/emulator-artifacts/api-${api_level}"
logcat_pid=''

test -s "$app_apk"
test -s "$test_apk"
mkdir -p "$output_dir"

cleanup() {
  if [[ -n "${logcat_pid:-}" ]]; then
    kill "$logcat_pid" >/dev/null 2>&1 || true
    wait "$logcat_pid" >/dev/null 2>&1 || true
  fi
}

capture_state() {
  local label=${1:-state}
  mkdir -p "$output_dir"
  {
    printf 'label=%s\n' "$label"
    date -u '+captured_at=%Y-%m-%dT%H:%M:%SZ'
    adb get-state || true
    adb shell getprop sys.boot_completed | tr -d '\r' | sed 's/^/boot_completed=/' || true
    adb shell pidof "$package_name" | tr -d '\r' | sed 's/^/pid=/' || true
  } >"$output_dir/${label}-summary.txt" 2>&1 || true
  adb exec-out screencap -p >"$output_dir/${label}.png" 2>/dev/null || true
  adb shell dumpsys activity activities >"$output_dir/${label}-activity.txt" 2>&1 || true
  adb shell dumpsys window windows >"$output_dir/${label}-window-windows.txt" 2>&1 || true
  adb shell dumpsys window displays >"$output_dir/${label}-window-displays.txt" 2>&1 || true
  adb shell dumpsys package "$package_name" >"$output_dir/${label}-package.txt" 2>&1 || true
  adb logcat -d -v time >"$output_dir/${label}-logcat.txt" 2>&1 || true
}

on_error() {
  local status=$?
  capture_state failure || true
  echo "Emulator smoke failed on API ${api_level}; diagnostics saved to ${output_dir}" >&2
  exit "$status"
}
trap on_error ERR
trap cleanup EXIT

app_is_visible() {
  local label=$1
  adb shell dumpsys activity activities >"$output_dir/${label}-activity.txt" 2>&1 || true
  adb shell dumpsys window windows >"$output_dir/${label}-window-windows.txt" 2>&1 || true
  adb shell dumpsys window displays >"$output_dir/${label}-window-displays.txt" 2>&1 || true

  if grep -E 'mCurrentFocus=|mFocusedApp=|mResumedActivity:|topResumedActivity=' \
      "$output_dir/${label}-activity.txt" \
      "$output_dir/${label}-window-windows.txt" \
      "$output_dir/${label}-window-displays.txt" 2>/dev/null | grep -F "$package_name" >/dev/null; then
    return 0
  fi
  return 1
}

wait_for_app_visible() {
  local label=$1
  for _ in {1..15}; do
    if app_is_visible "$label"; then
      return 0
    fi
    sleep 1
  done
  capture_state "${label}-not-visible" || true
  return 1
}

adb wait-for-device
{
  printf 'api_level=%s\n' "$api_level"
  adb shell getprop ro.build.version.release | tr -d '\r' | sed 's/^/android_release=/'
  adb shell getprop ro.product.cpu.abi | tr -d '\r' | sed 's/^/abi=/'
} >"$output_dir/environment.txt"

# Headless Google API images can surface stale system/launcher dialogs over the
# tested activity. Suppress background system error dialogs and close anything
# left from emulator boot, but still require the app to become visible below.
adb shell settings put global hide_error_dialogs 1
adb shell settings put global anr_show_background 0 >/dev/null 2>&1 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# Some API 26 images reject clearing one of the log buffers even though logcat itself works.
adb logcat -c >/dev/null 2>&1 || true
adb logcat -v time >"$output_dir/live-logcat.txt" 2>&1 &
logcat_pid=$!

adb install -r "$app_apk"
adb install -r "$test_apk"

# Exercise the real cold-start path with clean app data and no pre-granted location permission.
adb shell pm clear "$package_name" >/dev/null
first_launch=$(adb shell am start -W -S -n "$activity_name")
printf '%s\n' "$first_launch" | tee "$output_dir/first-launch-am-start.txt"
grep -F 'Status: ok' <<<"$first_launch"
sleep 3
capture_state first-launch
if ! wait_for_app_visible first-launch; then
  echo 'First launch was not visible; retrying once with a clean task before failing.' | tee "$output_dir/first-launch-retry-note.txt"
  adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
  retry_launch=$(adb shell am start -W -S -n "$activity_name")
  printf '%s\n' "$retry_launch" | tee "$output_dir/first-launch-retry-am-start.txt"
  grep -F 'Status: ok' <<<"$retry_launch"
  sleep 3
  capture_state first-launch-retry
  wait_for_app_visible first-launch-retry
fi
adb shell am force-stop "$package_name"
adb shell pm clear "$package_name" >/dev/null

# A launcher ANR may have been queued before hide_error_dialogs took effect.
# Close it once more immediately before ActivityScenario starts the UI suite.
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true

# Deterministic debug-only fixtures drive the UI suite; production builds cannot enter them.
instrumentation_output="$output_dir/instrumentation.txt"
set +e
adb shell am instrument -w -r "$test_runner" | tee "$instrumentation_output"
instrumentation_status=${PIPESTATUS[0]}
set -e
adb pull "/sdcard/Android/data/$package_name/files/navigation-after-ime.png" "$output_dir/navigation-after-ime.png" >/dev/null 2>&1 || true
adb pull "/sdcard/Android/data/$package_name/files/navigation-after-ime.txt" "$output_dir/navigation-after-ime.txt" >/dev/null 2>&1 || true
capture_state post-instrumentation
if (( instrumentation_status != 0 )); then
  exit "$instrumentation_status"
fi
grep -E 'OK \([0-9]+ tests?\)' "$instrumentation_output"
if grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$instrumentation_output"; then
  exit 1
fi

capture_fixture() {
  local screen=$1
  local name=$2
  local expected=$3
  local dark=${4:-false}
  local start_output

  adb shell am force-stop "$package_name"
  if [[ "$dark" == 'true' ]]; then
    start_output=$(adb shell am start -W -n "$activity_name" --es qa_screen "$screen" --ez qa_dark true)
  else
    start_output=$(adb shell am start -W -n "$activity_name" --es qa_screen "$screen")
  fi
  printf '%s\n' "$start_output" >"$output_dir/${name}-am-start.txt"
  grep -F 'Status: ok' <<<"$start_output"
  # MapLibre can restore the route overlay before vector tiles finish loading,
  # especially on API 35. Give screenshot fixtures enough time to represent
  # the settled UI instead of capturing a transient map surface.
  sleep 5
  adb exec-out screencap -p >"$output_dir/${name}.png"
  test -s "$output_dir/${name}.png"
  adb shell uiautomator dump /sdcard/vremyahodom-window.xml >/dev/null
  adb pull /sdcard/vremyahodom-window.xml "$output_dir/${name}.xml" >/dev/null
  grep -F "$expected" "$output_dir/${name}.xml"
}

capture_fixture home home 'Куда едем?'
capture_fixture nearby nearby 'Театральная площадь'
capture_fixture error plan-error 'Проверьте адрес'
capture_fixture routes route-options 'Бабушкинская'
capture_fixture route_map selected-route-map 'Бабушкинская'
capture_fixture trip active-trip 'В пути'
capture_fixture settings settings 'Настройки'
capture_fixture home dark-theme 'Куда едем?' true

adb shell am force-stop "$package_name"
app_info=$(adb shell am start -W -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$package_name")
printf '%s\n' "$app_info" >"$output_dir/app-info-am-start.txt"
grep -F 'Status: ok' <<<"$app_info"
sleep 2
adb exec-out screencap -p >"$output_dir/launcher-icon-app-info.png"
test -s "$output_dir/launcher-icon-app-info.png"
adb shell uiautomator dump /sdcard/vremyahodom-app-info.xml >/dev/null
adb pull /sdcard/vremyahodom-app-info.xml "$output_dir/launcher-icon-app-info.xml" >/dev/null
grep -F 'Время ходом' "$output_dir/launcher-icon-app-info.xml"

if adb logcat -d -v brief | grep -A 12 'FATAL EXCEPTION' | grep -F "$package_name"; then
  echo 'Fatal VremyaHodom exception detected in emulator logcat' >&2
  exit 1
fi

capture_state success

echo "Emulator smoke passed on API $api_level"
