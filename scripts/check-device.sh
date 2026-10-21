#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  printf 'Set ANDROID_SERIAL to the explicit authorized test target. Discover targets with adb devices -l; this script never selects one automatically.\n' >&2
  exit 2
fi
command -v adb >/dev/null || { printf 'adb is unavailable. Install SDK platform-tools and add it to PATH.\n' >&2; exit 1; }
[[ "$(adb -s "$ANDROID_SERIAL" get-state)" == device ]] || { printf 'Selected target is not connected and authorized.\n' >&2; exit 1; }
device_api="$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ && "$device_api" -ge 31 ]] || { printf 'API31+ is required; selected target reports %s.\n' "$device_api" >&2; exit 1; }
printf 'Running instrumented checks on explicit target %s (API %s).\n' "$ANDROID_SERIAL" "$device_api"
./gradlew --no-daemon --console=plain \
  "-Pandroid.injected.device.serial=$ANDROID_SERIAL" \
  :platform:connectedOfflineDebugAndroidTest :platform:connectedConnectedDebugAndroidTest \
  :app:connectedOfflineDebugAndroidTest :app:connectedConnectedDebugAndroidTest
printf '%s\n' 'Instrumented tests completed; inspect build/outputs/androidTest-results for failures and skipped tests.' 'This command does not claim model loading/inference or complete manual/device release qualification. Run check-model.sh separately with an approved model.'
