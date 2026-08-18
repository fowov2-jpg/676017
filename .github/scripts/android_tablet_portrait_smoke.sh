#!/usr/bin/env bash
set -euo pipefail

artifact_dir=${1:?artifact directory is required}
api_level=${2:-35}
group=${3:-core}
package_name='app.humanrouter'
test_package='app.humanrouter.test'
activity_name='app.humanrouter/.MainActivity'
test_runner='app.humanrouter.test/androidx.test.runner.AndroidJUnitRunner'
selector_timeout_seconds=${VH_SELECTOR_TIMEOUT_SECONDS:-120}
app_apk=$(find "$artifact_dir" -type f -name 'app-debug.apk' -print -quit)
test_apk=$(find "$artifact_dir" -type f -name 'app-debug-androidTest.apk' -print -quit)
output_root="android/emulator-artifacts/responsive-api-${api_level}"
out="$output_root/tablet-portrait-${group}"

case "$group" in
  core|ui|transit) ;;
  *)
    echo "Unknown tablet portrait group: $group" >&2
    exit 2
    ;;
esac

test -s "$app_apk"
test -s "$test_apk"
mkdir -p "$out"

cleanup() {
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
  adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
  adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
  adb shell am force-stop "$test_package" >/dev/null 2>&1 || true
}
trap cleanup EXIT

adb wait-for-device
adb shell settings put global hide_error_dialogs 1
adb shell settings put global anr_show_background 0 >/dev/null 2>&1 || true
# Match the already-green API26/API35 emulator harness: a freshly booted headless Google image can
# keep a stale launcher/system dialog or keyguard above the first test activity. Close those system
# surfaces explicitly, then still require each instrumentation assertion to pass normally.
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r "$app_apk"
adb install -r "$test_apk"
adb shell wm size 1080x1728
adb shell wm density 216
adb shell settings put system font_scale 1.0
adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true
sleep 2

{
  printf 'label=tablet-portrait\n'
  printf 'group=%s\n' "$group"
  printf 'selector_timeout_seconds=%s\n' "$selector_timeout_seconds"
  printf 'requested_size=1080x1728\n'
  printf 'requested_density=216\n'
  printf 'font_scale=1.0\n'
  printf 'expected_width_dp=800\n'
  printf 'expected_height_dp=1280\n'
  adb shell wm size | tr -d '\r'
  adb shell wm density | tr -d '\r'
  adb shell settings get system font_scale | tr -d '\r'
} >"$out/environment.txt"

instrumentation_output="$out/instrumentation.txt"
: >"$instrumentation_output"

capture_selector_diagnostics() {
  local reason=$1
  local selector=$2
  local safe
  safe=$(printf '%s' "$selector" | tr -cs 'A-Za-z0-9._-' '_')
  printf '\n===== %s DIAGNOSTICS: %s =====\n' "${reason^^}" "$selector" >>"$instrumentation_output"
  adb logcat -d -v threadtime >"$out/${reason}-${safe}-logcat.txt" 2>&1 || true
  adb shell dumpsys window windows >"$out/${reason}-${safe}-window-windows.txt" 2>&1 || true
  adb shell dumpsys window displays >"$out/${reason}-${safe}-window-displays.txt" 2>&1 || true
  adb shell dumpsys activity activities >"$out/${reason}-${safe}-activity.txt" 2>&1 || true
  adb shell dumpsys input_method >"$out/${reason}-${safe}-ime.txt" 2>&1 || true
  adb exec-out screencap -p >"$out/${reason}-${safe}.png" 2>/dev/null || true
}

run_test_selector() {
  local selector=$1
  local one_log
  one_log=$(mktemp)

  adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
  adb shell am force-stop "$test_package" >/dev/null 2>&1 || true
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  adb shell pm clear "$package_name" >/dev/null
  sleep 0.35

  set +e
  timeout --signal=TERM --kill-after=10s "${selector_timeout_seconds}s" \
    adb shell am instrument -w -r -e class "$selector" "$test_runner" | tee "$one_log"
  local status=${PIPESTATUS[0]}
  set -e

  {
    printf '\n===== %s =====\n' "$selector"
    cat "$one_log"
  } >>"$instrumentation_output"

  if (( status == 124 || status == 137 )); then
    echo "Instrumentation selector timed out after ${selector_timeout_seconds}s: $selector" >&2
    capture_selector_diagnostics timeout "$selector"
    adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
    adb shell am force-stop "$test_package" >/dev/null 2>&1 || true
    rm -f "$one_log"
    return 124
  fi
  if (( status != 0 )); then
    capture_selector_diagnostics failure "$selector"
    rm -f "$one_log"
    return "$status"
  fi
  grep -E 'OK \([0-9]+ tests?\)' "$one_log"
  if grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$one_log"; then
    capture_selector_diagnostics failure "$selector"
    rm -f "$one_log"
    return 1
  fi
  rm -f "$one_log"
}

pull_stop_sheet_screenshots() {
  local device_dir="/sdcard/Android/data/${package_name}/files/stop-sheet"
  mkdir -p "$out/stop-sheet"
  adb pull "$device_dir/." "$out/stop-sheet/" >/dev/null
  test -s "$out/stop-sheet/stop-bus-directions.png"
  test -s "$out/stop-sheet/stop-metro-directions.png"
}

capture_fixture() {
  local screen=$1
  local name=$2
  local expected=$3
  adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
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

case "$group" in
  core)
    selectors=(
      'app.humanrouter.GpsRouteReplayInstrumentationTest'
      'app.humanrouter.InteractionStabilitySmokeTest'
      'app.humanrouter.LauncherVisibilityTest'
      'app.humanrouter.MainActivitySmokeTest#homeNavigationSettingsAndRecreationRemainUsable'
      'app.humanrouter.MainActivitySmokeTest#locationPermissionStatesKeepManualOriginAvailable'
    )
    ;;
  ui)
    selectors=(
      'app.humanrouter.MainActivitySmokeTest#routeOptionsFiltersFavoritesAndTripFlowAreInteractive'
      'app.humanrouter.MainActivitySmokeTest#darkThemeAndRotationPreserveTheMainScreen'
      'app.humanrouter.MainActivitySmokeTest#addressErrorUsesACompactSheet'
      'app.humanrouter.ReferenceProductUiSmokeTest'
      'app.humanrouter.RouteSheetInteractionTest'
    )
    ;;
  transit)
    selectors=(
      'app.humanrouter.TransitStopInteractionInstrumentationTest'
      'app.humanrouter.TransitVisualSystemSmokeTest'
      'app.humanrouter.UiPolishSmokeTest'
      'app.humanrouter.UnifiedUiSmokeTest'
      'app.humanrouter.routing.RoutingModesInstrumentationTest'
    )
    ;;
esac

for selector in "${selectors[@]}"; do
  run_test_selector "$selector"
  if [[ "$selector" == 'app.humanrouter.TransitStopInteractionInstrumentationTest' ]]; then
    pull_stop_sheet_screenshots
  fi
done

if [[ "$group" == 'ui' ]]; then
  capture_fixture home home 'Куда едем?'
  capture_fixture routes route-options 'Бабушкинская'
  capture_fixture trip active-trip 'В пути'
  capture_fixture settings settings 'Настройки'
fi

if adb logcat -d -v brief | grep -A 12 'FATAL EXCEPTION' | grep -F "$package_name"; then
  echo "Fatal app exception detected for tablet portrait group $group" >&2
  exit 1
fi

echo "Tablet portrait $group smoke passed on API $api_level"
