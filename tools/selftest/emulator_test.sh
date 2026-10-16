#!/usr/bin/env bash
# Runs the real Gossi APK on an emulator: plays a recorded conversation through the offline
# engine (instead of the mic) and saves screenshots + the transcript log.
# Usage: emulator_test.sh APK MODEL_FILE WAV OUT_DIR
set -uo pipefail
APK=$1; MODEL=$2; WAV=$3; OUT=$4
mkdir -p "$OUT"
PKG=app.gossi

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "screenshot $1"; }
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, subprocess, sys
xml = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(sys.argv[2]), xml)
if m:
    x1, y1, x2, y2 = map(int, m.groups())
    subprocess.run(["adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)])
    print("tapped", sys.argv[2])
else:
    print("not found:", sys.argv[2])
PY
}

adb install -r -g "$APK"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
# Copy files into the app's private storage (piped: apps can't read /data/local/tmp directly).
adb shell run-as $PKG mkdir -p files/models
adb push "$MODEL" /data/local/tmp/model.bin >/dev/null
adb push "$WAV" /data/local/tmp/selftest.wav >/dev/null
adb shell "cat /data/local/tmp/model.bin | run-as $PKG sh -c 'cat > files/models/ggml-base-q5_1.bin'"
adb shell "cat /data/local/tmp/selftest.wav | run-as $PKG sh -c 'cat > files/selftest.wav'"
adb shell run-as $PKG ls -la files files/models

adb logcat -c
adb shell am start -W -n $PKG/.ui.MainActivity
sleep 5; shot 01_home
adb shell am start -n $PKG/.ui.MainActivity --ez gossi_selftest true --es gossi_model BASE
sleep 10; shot 02_started
sleep 25; shot 03_conversation
sleep 30; shot 04_conversation_later
sleep 20; shot 05_end
tap_text "Settings"; sleep 3; shot 06_settings
adb shell input keyevent KEYCODE_BACK; sleep 1
adb logcat -d -s GossiTranscript:I GossiWhisper:I AndroidRuntime:E GossiMic:* > "$OUT/logcat.txt"
adb logcat -d | grep -iE "gossi|FATAL" | tail -200 > "$OUT/logcat_all.txt"
echo "--- transcript ---"; cat "$OUT/logcat.txt"
exit 0
