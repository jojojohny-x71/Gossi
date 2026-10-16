#!/usr/bin/env bash
# Install Gossi on a phone connected over adb and watch it work.
# Usage: tools/phone_test.sh path/to/Gossi.apk
set -e
APK=${1:?usage: phone_test.sh Gossi.apk}
adb devices -l
adb install -r -g "$APK"
adb shell am start -n app.gossi/.ui.MainActivity
echo
echo "Gossi is open. In the app: Settings → Offline model → Download (Balanced), then Start translating."
echo "Speak (or play a Hindi/Tamil/Kannada/Malayalam video) near the phone. Live log below — Ctrl+C to stop."
echo
adb logcat -c
adb logcat -v time -s GossiTranscript:I GossiWhisper:I GossiMic:* GossiEarbudMic:* GossiRecognizer:* AndroidRuntime:E
