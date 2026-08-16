#!/usr/bin/env bash
set -euo pipefail

artifact_dir=${1:?artifact directory is required}
output_dir=${2:-ui-smoke}
package_name='app.humanrouter'
activity_name='app.humanrouter/.MainActivity'
apk=$(find "$artifact_dir" -type f -name 'app-debug.apk' -print -quit)

test -n "$apk"
test -s "$apk"
mkdir -p "$output_dir"
printf 'started_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$output_dir/status.txt"

adb wait-for-device
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
adb shell settings put global anr_show_background 0 >/dev/null 2>&1 || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true

adb install -r "$apk"
adb shell pm clear "$package_name" >/dev/null
adb shell pm grant "$package_name" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1 || true
adb shell pm grant "$package_name" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
adb shell pm grant "$package_name" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

start_output=$(adb shell am start -W -n "$activity_name")
printf '%s\n' "$start_output" | tee "$output_dir/start.txt"
grep -F 'Status: ok' "$output_dir/start.txt"

# Compose semantics and the map surface can settle a little after Activity startup.
# Retry the accessibility dump rather than treating the first frame as authoritative.
ui_ready=false
for attempt in {1..8}; do
  sleep 2
  adb shell uiautomator dump /sdcard/vremyahodom-window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/vremyahodom-window.xml "$output_dir/window.xml" >/dev/null 2>&1 || true
  if [[ -s "$output_dir/window.xml" ]] \
    && grep -F 'Карта' "$output_dir/window.xml" >/dev/null \
    && grep -F 'Маршруты' "$output_dir/window.xml" >/dev/null \
    && grep -F 'Транспорт' "$output_dir/window.xml" >/dev/null \
    && grep -F 'Избранное' "$output_dir/window.xml" >/dev/null; then
    ui_ready=true
    printf 'ui_ready_attempt=%s\n' "$attempt" >> "$output_dir/status.txt"
    break
  fi
done

test "$ui_ready" = true
adb exec-out screencap -p > "$output_dir/home.png"
test -s "$output_dir/home.png"
adb shell wm size | tee "$output_dir/screen-size.txt"
adb shell wm density | tee "$output_dir/screen-density.txt"

python - "$output_dir" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

output_dir = Path(sys.argv[1])
root = ET.parse(output_dir / 'window.xml').getroot()
size_text = (output_dir / 'screen-size.txt').read_text(encoding='utf-8')
matches = re.findall(r'(\d+)x(\d+)', size_text)
if not matches:
    raise SystemExit('Unable to determine emulator screen size')
screen_w, screen_h = map(int, matches[-1])

labels = ('Карта', 'Маршруты', 'Транспорт', 'Избранное')
centres = []
for label in labels:
    candidates = []
    for node in root.iter('node'):
        if node.attrib.get('text') == label or node.attrib.get('content-desc') == label:
            bounds = node.attrib.get('bounds', '')
            match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
            if match:
                x1, y1, x2, y2 = map(int, match.groups())
                candidates.append((x1, y1, x2, y2))
    if not candidates:
        raise SystemExit(f'Missing navigation destination in accessibility tree: {label}')

    # Text is typically the lowest matching semantics node. Use it to compare the
    # four destinations rather than relying on implementation-specific icon bounds.
    x1, y1, x2, y2 = max(candidates, key=lambda b: b[3])
    if y1 < screen_h * 0.70 or y2 > screen_h:
        raise SystemExit(
            f'Navigation destination is outside expected bottom band: {label} {(x1, y1, x2, y2)}'
        )
    centres.append(((x1 + x2) / 2, (y1 + y2) / 2))

vertical_spread = max(y for _, y in centres) - min(y for _, y in centres)
if vertical_spread > screen_h * 0.04:
    raise SystemExit(f'Navigation destinations are vertically misaligned: spread={vertical_spread}')

horizontal = [x for x, _ in centres]
if horizontal != sorted(horizontal):
    raise SystemExit(f'Navigation destinations are not ordered left-to-right: {horizontal}')

print(f'Material navigation geometry OK: screen={screen_w}x{screen_h}, centres={centres}')
PY

adb shell dumpsys activity activities | grep -F "$package_name/.MainActivity"
if adb logcat -d -v brief | grep -A 15 'FATAL EXCEPTION' | grep -F "$package_name"; then
  echo 'Fatal VremyaHodom exception detected in emulator logcat' >&2
  exit 1
fi

printf 'result=success\n' >> "$output_dir/status.txt"
echo 'Android UI smoke passed'
