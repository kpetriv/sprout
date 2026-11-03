#!/usr/bin/env bash
# Enrols a fingerprint on a running emulator, so Sprout's strong-biometric parent key (ADR-0004) can be created and
# every parent money event can be signed. Used for the M2 emulator walkthrough (docs/milestones/M2.md).
#
# Usage: tools/emulator/enrol-fingerprint.sh [finger-id] [device-pin]
#   finger-id   the emulator finger to enrol (default 1); later satisfy prompts with `adb -e emu finger touch <id>`
#   device-pin  the screen-lock PIN to set or use (default 1234); a fingerprint needs a screen lock
#
# Steps (tested on the Pixel_7 AVD, API 33, google_apis_playstore):
#   1. set a device PIN with `adb shell locksettings set-pin` (skipped when that PIN is already set);
#   2. open the system enrolment flow with `am start -a android.settings.FINGERPRINT_ENROLL`;
#   3. confirm the PIN, accept the intro ("I agree" / "More" / "Next" / "Start"), whatever this image shows; if
#      the enrol screen closes at once (seen after deleting a fingerprint), go through Security settings instead;
#   4. touch the virtual sensor with `adb -e emu finger touch <id>` until "Fingerprint added";
#   5. press "Done" and check `dumpsys fingerprint` reports an enrolled template.
# The buttons are found by text in a `uiautomator dump`, so no screen coordinates are hard-coded.
set -euo pipefail

FINGER="${1:-1}"
PIN="${2:-1234}"
ADB="${ADB:-adb}"
DUMP=/sdcard/sprout-ui.xml

ui_dump() {
  "$ADB" shell uiautomator dump "$DUMP" >/dev/null 2>&1 || true
  "$ADB" shell cat "$DUMP" 2>/dev/null
}

# Taps the first node whose text or content-desc matches $1 (a Python regex, case-insensitive). Returns 1 if none.
tap_text() {
  local pattern="$1" xml point
  xml="$(ui_dump)"
  point="$(PATTERN="$pattern" python3 -I -c '
import os, re, sys
xml = sys.stdin.read()
pat = re.compile(os.environ["PATTERN"], re.I)
for m in re.finditer(r"<node [^>]*>", xml):
    node = m.group(0)
    text = re.search(r"\btext=\"([^\"]*)\"", node)
    desc = re.search(r"content-desc=\"([^\"]*)\"", node)
    label = (text.group(1) if text else "") or (desc.group(1) if desc else "")
    if label and pat.fullmatch(label.strip()):
        b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
' <<<"$xml")"
  [[ -n "$point" ]] || return 1
  # shellcheck disable=SC2086
  "$ADB" shell input tap $point
}

screen_has() {
  ui_dump | grep -qiE "$1"
}

enrolled() {
  "$ADB" shell dumpsys fingerprint 2>/dev/null | grep -qE '"count":[1-9]|count=[1-9]'
}

if enrolled; then
  echo "A fingerprint is already enrolled."
  exit 0
fi

echo "1. Device PIN"
if "$ADB" shell locksettings verify --old "$PIN" 2>&1 | grep -qi "verified"; then
  echo "   already set"
else
  "$ADB" shell locksettings set-pin "$PIN"
fi

echo "2. Opening fingerprint enrolment"
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell am start -a android.settings.FINGERPRINT_ENROLL >/dev/null
sleep 3

# Confirms the device PIN and taps through the intro until the "touch the sensor" screen. Returns 1 if it never shows.
walk_to_sensor() {
  for _ in $(seq 1 12); do
    if screen_has 'Re-enter your PIN|Enter your (device )?PIN|Confirm your PIN|Use your PIN'; then
      "$ADB" shell input text "$PIN"
      "$ADB" shell input keyevent KEYCODE_ENTER
      sleep 2
    elif screen_has 'Touch the sensor|Put your finger|Lift, then touch again|Place your finger'; then
      return 0
    elif tap_text 'I agree|More|Next|Start|Continue|Agree|Add fingerprint'; then
      sleep 2
    else
      sleep 1
    fi
  done
  return 1
}

echo "3. Confirming the PIN and accepting the intro"
if ! walk_to_sensor; then
  # After a fingerprint was deleted, some images close FINGERPRINT_ENROLL at once: go through Security settings.
  echo "   enrolment screen did not open; trying Security > fingerprint settings"
  "$ADB" shell am start -a android.settings.SECURITY_SETTINGS >/dev/null
  sleep 3
  tap_text 'Pixel Imprint|Fingerprint.*' || true
  sleep 2
  walk_to_sensor || true
fi

echo "4. Touching the sensor (finger $FINGER)"
for _ in $(seq 1 20); do
  "$ADB" -e emu finger touch "$FINGER" >/dev/null
  sleep 1
  "$ADB" -e emu finger remove "$FINGER" >/dev/null 2>&1 || true
  sleep 1
  if screen_has 'Fingerprint added|All set|Done'; then
    break
  fi
done

echo "5. Finishing"
tap_text 'Done|OK' || true
sleep 1
"$ADB" shell input keyevent KEYCODE_HOME

if enrolled; then
  echo "Fingerprint $FINGER enrolled. Satisfy prompts with: $ADB -e emu finger touch $FINGER"
else
  echo "Enrolment did not finish; check the screen (adb exec-out screencap -p > screen.png)." >&2
  exit 1
fi
