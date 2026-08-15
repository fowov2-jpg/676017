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

test -s "$app_apk"
test -s "$test_apk"
mkdir -p "$output_dir"

adb wait-for-device
{
  printf 'api_level=%s\n' "$api_level"
  adb shell getprop ro.build.version.release | tr -d '\r' | sed 's/^/android_release=/'
  adb shell getprop ro.product.cpu.abi | tr -d '\r' | sed 's/^/abi=/'
} >"$output_dir/environment.txt"

# Headless Google API images can surface a stale Pixel Launcher ANR over the
# tested activity.  It is unrelated to the app but steals window focus from
# Espresso, so suppress background system error dialogs and close any dialog
# that was created while the emulator was still booting.
adb shell settings put global hide_error_dialogs 1
adb shell settings put global anr_show_background 0 >/dev/null 2>&1 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# Some API 26 images reject clearing one of the log buffers even though logcat itself works.
adb logcat -c >/dev/null 2>&1 || true
adb install -r "$app_apk"
adb install -r "$test_apk"

# Exercise the real cold-start path with clean app data and no pre-granted location permission.
adb shell pm clear "$package_name" >/dev/null
first_launch=$(adb shell am start -W -n "$activity_name")
printf '%s\n' "$first_launch"
grep -F 'Status: ok' <<<"$first_launch"
sleep 2
adb exec-out screencap -p >"$output_dir/first-launch.png"
test -s "$output_dir/first-launch.png"
adb shell dumpsys activity activities | grep -F "$package_name/.MainActivity"
focus_dump="$output_dir/first-launch-window.txt"
focus_ok=false
for _ in {1..10}; do
  adb shell dumpsys window displays >"$focus_dump"
  if grep -F 'mCurrentFocus=' "$focus_dump" | grep -F "$package_name" >/dev/null; then
    focus_ok=true
    break
  fi
  sleep 1
done
test "$focus_ok" = true
adb shell am force-stop "$package_name"
adb shell pm clear "$package_name" >/dev/null

# A launcher ANR may have been queued before hide_error_dialogs took effect.
# Close it once more immediately before ActivityScenario starts the UI suite.
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true

# Deterministic debug-only fixtures drive the UI suite; production builds cannot enter them.
instrumentation_output="$output_dir/instrumentation.txt"
adb shell am instrument -w -r "$test_runner" | tee "$instrumentation_output"
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
  grep -F 'Status: ok' <<<"$start_output"
  sleep 2
  adb exec-out screencap -p >"$output_dir/${name}.png"
  test -s "$output_dir/${name}.png"
  adb shell uiautomator dump /sdcard/vremyahodom-window.xml >/dev/null
  adb pull /sdcard/vremyahodom-window.xml "$output_dir/${name}.xml" >/dev/null
  grep -F "$expected" "$output_dir/${name}.xml"
}

capture_fixture home home 'Куда едем?'
capture_fixture nearby nearby 'Театральная площадь'
capture_fixture routes route-options 'Варианты маршрута'
capture_fixture route_map selected-route-map 'Бабушкинская'
capture_fixture trip active-trip 'В пути'
capture_fixture settings settings 'Настройки'
capture_fixture home dark-theme 'Куда едем?' true

adb shell am force-stop "$package_name"
app_info=$(adb shell am start -W -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$package_name")
grep -F 'Status: ok' <<<"$app_info"
sleep 2
adb exec-out screencap -p >"$output_dir/launcher-icon-app-info.png"
test -s "$output_dir/launcher-icon-app-info.png"
adb shell uiautomator dump /sdcard/vremyahodom-app-info.xml >/dev/null
adb pull /sdcard/vremyahodom-app-info.xml "$output_dir/launcher-icon-app-info.xml" >/dev/null
grep -F 'ВремяХодом' "$output_dir/launcher-icon-app-info.xml"

if adb logcat -d -v brief | grep -A 12 'FATAL EXCEPTION' | grep -F "$package_name"; then
  echo 'Fatal VremyaHodom exception detected in emulator logcat' >&2
  exit 1
fi

echo "Emulator smoke passed on API $api_level"
