#!/usr/bin/env bash
# Real synthetic chat-controller regression on one explicitly authorized target.
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  printf 'Set ANDROID_SERIAL to the explicit authorized chat regression target.\n' >&2
  exit 2
fi
command -v adb >/dev/null || { printf 'adb is unavailable.\n' >&2; exit 1; }
[[ "$(adb -s "$ANDROID_SERIAL" get-state)" == device ]] || { printf 'Explicit Android target is unavailable.\n' >&2; exit 1; }
device_api="$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ && "$device_api" -ge 31 ]] || { printf 'API31+ target required.\n' >&2; exit 1; }
printf 'Chat regression target: %s (API %s).\n' "$ANDROID_SERIAL" "$device_api"
# Remove only the known generated app report roots so a no-source invocation
# cannot satisfy this gate with a previous execution's XML.
python3 - <<'PY'
import runpy
import shutil
gate = runpy.run_path('scripts/verify-chat-regression.py')
for variant in ('offlineDebug', 'connectedDebug'):
    for path in gate['result_roots'](variant):
        gate['require_safe_result_path'](path)
        if path.exists():
            shutil.rmtree(path)
PY
if ./gradlew --no-daemon --console=plain --stacktrace \
  "-Pandroid.injected.device.serial=$ANDROID_SERIAL" \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.privateassistant.app.AssistantChatLifecycleTest' \
  :app:connectedOfflineDebugAndroidTest :app:connectedConnectedDebugAndroidTest; then
  : # Require the exact non-skipped test identities in the freshly produced XML.
else
  gradle_status=$?
  python3 scripts/verify-chat-regression.py --diagnose || true
  exit "$gradle_status"
fi
python3 scripts/verify-chat-regression.py
