#!/usr/bin/env bash
set -euo pipefail

artifact_dir=${1:?artifact directory is required}
api_level=${2:-35}
viewport_filter=${3:-all}
package_name='app.humanrouter'
activity_name='app.humanrouter/.MainActivity'
test_runner='app.humanrouter.test/androidx.test.runner.AndroidJUnitRunner'
app_apk=$(find "$artifact_dir" -type f -name 'app-debug.apk' -print -quit)
test_apk=$(find "$artifact_dir" -type f -name 'app-debug-androidTest.apk' -print -quit)
output_root="android/emulator-artifacts/responsive-api-${api_level}"

test -s "$app_apk"
test -s "$test_apk"
mkdir -p "$output_root"

case "$viewport_filter" in
  all|compact-phone|compact-phone-large-text|tablet-portrait|tablet-landscape) ;;
  *)
    echo "Unknown responsive viewport: $viewport_filter" >&2
    exit 2
    ;;
esac

adb wait-for-device
adb shell settings put global hide_error_dialogs 1
adb shell settings put global anr_show_background 0 >/dev/null 2>&1 || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r "$app_apk"
adb install -r "$test_apk"

restore_network() {
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
}

cleanup() {
  restore_network
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
  adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
  adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

capture_fixture() {
  local out=$1
  local screen=$2
  local name=$3
  local expected=$4
  adb shell am force-stop "$package_name" >/dev/null
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

run_test_selector() {
  local selector=$1
  local log=$2

  # Every scenario starts from a clean target process/data/window state. This prevents a permission,
  # IME or Activity window from a previous independent JUnit scenario stealing focus from the next
  # one. InteractionStabilitySmokeTest still exercises repeated transitions inside one Activity, so
  # real UI race/idle regressions are not hidden by this isolation.
  adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  adb shell pm clear "$package_name" >/dev/null
  sleep 0.35

  local one_log
  one_log=$(mktemp)
  set +e
  adb shell am instrument -w -r -e class "$selector" "$test_runner" | tee "$one_log"
  local status=${PIPESTATUS[0]}
  set -e
  {
    printf '\n===== %s =====\n' "$selector"
    cat "$one_log"
  } >>"$log"
  if (( status != 0 )); then
    rm -f "$one_log"
    return "$status"
  fi
  grep -E 'OK \([0-9]+ tests?\)' "$one_log"
  if grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$one_log"; then
    rm -f "$one_log"
    return 1
  fi
  rm -f "$one_log"
}

pull_stop_sheet_screenshots() {
  local out=$1
  local device_dir="/sdcard/Android/data/${package_name}/files/stop-sheet"
  mkdir -p "$out/stop-sheet"
  adb pull "$device_dir/." "$out/stop-sheet/" >/dev/null
  test -s "$out/stop-sheet/stop-bus-directions.png"
  test -s "$out/stop-sheet/stop-metro-directions.png"
}

run_offline_address_gate() {
  local log=$1
  adb shell svc wifi disable >/dev/null 2>&1 || true
  adb shell svc data disable >/dev/null 2>&1 || true
  sleep 1
  {
    printf '\n===== OFFLINE ADDRESS ENVIRONMENT =====\n'
    printf 'wifi='; adb shell dumpsys wifi | grep -m1 -E 'Wi-Fi is|WifiState' || true
    printf 'data='; adb shell dumpsys telephony.registry | grep -m1 -E 'mDataConnectionState|mDataConnectionNetworkType' || true
  } >>"$log"
  run_test_selector 'app.humanrouter.search.OfflineAddressIndexInstrumentationTest' "$log"
  restore_network
  sleep 1
}

run_viewport() {
  local label=$1
  local size=$2
  local density=$3
  local expected_width_dp=$4
  local expected_height_dp=$5
  local font_scale=${6:-1.0}
  local out="$output_root/$label"
  mkdir -p "$out"

  adb shell wm size "$size"
  adb shell wm density "$density"
  adb shell settings put system font_scale "$font_scale"
  adb shell am force-stop "$package_name" >/dev/null
  sleep 2

  {
    printf 'label=%s\n' "$label"
    printf 'requested_size=%s\n' "$size"
    printf 'requested_density=%s\n' "$density"
    printf 'font_scale=%s\n' "$font_scale"
    printf 'expected_width_dp=%s\n' "$expected_width_dp"
    printf 'expected_height_dp=%s\n' "$expected_height_dp"
    adb shell wm size | tr -d '\r'
    adb shell wm density | tr -d '\r'
    adb shell settings get system font_scale | tr -d '\r'
  } >"$out/environment.txt"

  local instrumentation_output="$out/instrumentation.txt"
  : >"$instrumentation_output"

  # One long instrumentation process can retain a system/IME/permission window between independent
  # test classes. Run every scenario in a clean target process instead. MainActivitySmokeTest is
  # split per method because it deliberately covers unrelated permission, error, route and rotation
  # states. RouteSheetInteractionTest additionally proves that the compact sheet can be expanded and
  # collapsed by the real handle on every viewport. TransitStopInteractionInstrumentationTest gates
  # the real stop/station -> direction -> «Отсюда / Сюда» flow on the same viewport matrix.
  local selectors=(
    'app.humanrouter.GpsRouteReplayInstrumentationTest'
    'app.humanrouter.InteractionStabilitySmokeTest'
    'app.humanrouter.LauncherVisibilityTest'
    'app.humanrouter.MainActivitySmokeTest#homeNavigationSettingsAndRecreationRemainUsable'
    'app.humanrouter.MainActivitySmokeTest#locationPermissionStatesKeepManualOriginAvailable'
    'app.humanrouter.MainActivitySmokeTest#routeOptionsFiltersFavoritesAndTripFlowAreInteractive'
    'app.humanrouter.MainActivitySmokeTest#darkThemeAndRotationPreserveTheMainScreen'
    'app.humanrouter.MainActivitySmokeTest#addressErrorUsesACompactSheet'
    'app.humanrouter.ReferenceProductUiSmokeTest'
    'app.humanrouter.RouteSheetInteractionTest'
    'app.humanrouter.TransitStopInteractionInstrumentationTest'
    'app.humanrouter.TransitVisualSystemSmokeTest'
    'app.humanrouter.UiPolishSmokeTest'
    'app.humanrouter.UnifiedUiSmokeTest'
    'app.humanrouter.routing.RoutingModesInstrumentationTest'
  )

  for selector in "${selectors[@]}"; do
    run_test_selector "$selector" "$instrumentation_output"
    if [[ "$selector" == 'app.humanrouter.TransitStopInteractionInstrumentationTest' ]]; then
      # Pull immediately: the next isolated scenario clears app data by design.
      pull_stop_sheet_screenshots "$out"
    fi
  done

  local expected_completed=27
  # The address lookup itself is viewport-independent, so run its two mandatory tests once on the
  # compact-phone pass with both Wi-Fi and cellular data explicitly disabled. This proves a normal
  # street/house lookup returns from runtime/address/address.sqlite before any online fallback.
  if [[ "$label" == 'compact-phone' ]]; then
    run_offline_address_gate "$instrumentation_output"
    expected_completed=29
  fi

  completed=$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' "$instrumentation_output" || true)
  if (( completed != expected_completed )); then
    echo "Expected $expected_completed completed responsive tests, got $completed for $label" >&2
    exit 1
  fi

  capture_fixture "$out" home home 'Куда едем?'
  capture_fixture "$out" nearby home-populated 'Театральная площадь'
  capture_fixture "$out" routes route-options 'Бабушкинская'
  capture_fixture "$out" trip active-trip 'В пути'
  capture_fixture "$out" settings settings 'Настройки'

  # Keep the approved phone references in the same artifact as the produced phone screenshots.
  # This makes the visual gate reproducible without relying on GitHub's text-only file API for JPEGs.
  if [[ "$label" == 'compact-phone' ]]; then
    cp docs/ui-reference/218231.jpg "$out/reference-218231.jpg"
    cp docs/ui-reference/218233.jpg "$out/reference-218233.jpg"
    test -s "$out/reference-218231.jpg"
    test -s "$out/reference-218233.jpg"
  fi

  if adb logcat -d -v brief | grep -A 12 'FATAL EXCEPTION' | grep -F "$package_name"; then
    echo "Fatal app exception detected for viewport $label" >&2
    exit 1
  fi
}

run_if_selected() {
  local label=$1
  shift
  if [[ "$viewport_filter" == all || "$viewport_filter" == "$label" ]]; then
    run_viewport "$label" "$@"
  fi
}

# Narrow phone: catches wrapping, overlapping sheets and small touch targets.
# 720/320 = 360dp, 1600/320 = 800dp.
run_if_selected compact-phone 720x1600 320 360 800 1.0

# Same narrow phone with accessibility-sized text. This specifically catches labels such as
# «Работа», «Рядом» and long transit names wrapping into neighboring controls.
run_if_selected compact-phone-large-text 720x1600 320 360 800 1.25

# Tablet portrait. 1080/216 = 800dp, 1728/216 = 1280dp.
run_if_selected tablet-portrait 1080x1728 216 800 1280 1.0

# Tablet landscape using the same APK and density: 1728/216 = 1280dp, 1080/216 = 800dp.
run_if_selected tablet-landscape 1728x1080 216 1280 800 1.0

echo "Responsive viewport $viewport_filter smoke passed on API $api_level"
