#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  printf 'Set ANDROID_SERIAL to the explicit authorized Android regression target.\n' >&2
  exit 2
fi
command -v adb >/dev/null || { printf 'adb is unavailable.\n' >&2; exit 1; }
[[ "$(adb -s "$ANDROID_SERIAL" get-state)" == device ]] || { printf 'Explicit Android target is unavailable.\n' >&2; exit 1; }
device_api="$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ && "$device_api" -ge 31 ]] || { printf 'API31+ target required.\n' >&2; exit 1; }
printf 'Vault regression target: %s (API %s).\n' "$ANDROID_SERIAL" "$device_api"
# Clear only generated XML output so a missing/no-source execution cannot reuse old evidence.
python3 - <<'PY'
import runpy
import shutil
result_roots = runpy.run_path('scripts/verify-unlock-regression.py')['result_roots']
for variant in ('offlineDebug', 'connectedDebug'):
    for path in result_roots(variant):
        if path.is_symlink():
            raise SystemExit('Android regression result directory must not be a symlink')
        if path.exists():
            shutil.rmtree(path)
PY
classes='com.privateassistant.platform.vault.SqlCipherInstrumentedTest,com.privateassistant.platform.vault.VaultEvidenceMigrationInstrumentedTest,com.privateassistant.platform.vault.StreamingCryptoInstrumentedTest,com.privateassistant.platform.vault.DurableFilesInstrumentedTest,com.privateassistant.platform.documents.OcrBoundaryInstrumentedTest,com.privateassistant.platform.documents.TesseractOcrInstrumentedTest'
if ./gradlew --no-daemon --console=plain --stacktrace \
  "-Pandroid.injected.device.serial=$ANDROID_SERIAL" \
  "-Pandroid.testInstrumentationRunnerArguments.class=$classes" \
  :platform:connectedOfflineDebugAndroidTest :platform:connectedConnectedDebugAndroidTest; then
  : # The result verifier below also rejects missing and skipped execution.
else
  gradle_status=$?
  python3 scripts/verify-unlock-regression.py --diagnose || true
  exit "$gradle_status"
fi
python3 scripts/verify-unlock-regression.py
