#!/usr/bin/env bash
# Opt-in real-model experiment on one explicitly selected device. No production vault is touched.
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ $# == 3 && "$3" == --accept-license ]] || fail 'Usage: ANDROID_SERIAL=<explicit-target> scripts/check-model-generation.sh <catalog-model-id> <local-model-file> --accept-license'
[[ -n "${ANDROID_SERIAL:-}" ]] || fail 'Set ANDROID_SERIAL to the explicit authorized model-probe target.'
for command in adb timeout python3 tee tr; do command -v "$command" >/dev/null || fail "Required command unavailable: $command"; done
[[ "$(adb -s "$ANDROID_SERIAL" get-state)" == device ]] || fail 'The explicit model-probe target is unavailable.'
device_api="$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ && "$device_api" -ge 31 ]] || fail 'An API31+ explicit target is required.'
report_directory="$project_root/artifacts/model-runtime-probe"
[[ ! -L "$project_root/artifacts" && ! -L "$report_directory" ]] || fail 'Report paths must not be symbolic links.'
mkdir -p "$report_directory"
readarray -t verified < <(python3 - "$1" "$2" "$report_directory/local-file-verification.json" <<'PY'
from pathlib import Path
import hashlib, json, re, sys
catalogue = json.load(open('config/models.json'))
models = [m for m in catalogue['models'] if m['id'] == sys.argv[1]]
if len(models) != 1:
    raise SystemExit('Exactly one reviewed experimental model is required')
m = models[0]
if (m.get('assessment') != 'experimental' or m['format'] != 'litertlm'
        or m['runtimeVersion'] != catalogue['runtime']['version'] or 'CPU' not in m['backends']
        or not re.fullmatch('[a-f0-9]{64}', m['sha256'])):
    raise SystemExit('Experimental artifact/runtime metadata is incomplete')
p = Path(sys.argv[2])
if p.is_symlink() or not p.is_file() or p.stat().st_size != m['byteCount']:
    raise SystemExit('Model file size/path check failed')
sha = hashlib.sha256()
with p.open('rb') as f:
    for block in iter(lambda: f.read(1024 * 1024), b''):
        sha.update(block)
if sha.hexdigest() != m['sha256']:
    raise SystemExit('Model file SHA-256 check failed')
report = Path(sys.argv[3])
if report.is_symlink():
    raise SystemExit('Report must not be a symbolic link')
report.write_text(json.dumps({'modelId': m['id'], 'revision': m['revision'], 'byteCount': m['byteCount'],
    'sha256': sha.hexdigest(), 'runtimeVersion': m['runtimeVersion'],
    'scope': 'Actual local public model bytes verified before upload; no model quality claim'}, indent=2) + '\n')
print(p.resolve())
print(m['id'])
print(m['sha256'])
print(m['byteCount'])
print(m['runtimeVersion'])
PY
)
[[ "${#verified[@]}" == 5 ]] || fail 'Local model verification failed; no native model execution was attempted.'
shopt -s nullglob
test_apks=("$project_root/platform/build/outputs/apk/androidTest/offline/debug/"*"-offline-debug-androidTest.apk")
shopt -u nullglob
[[ "${#test_apks[@]}" == 1 && -f "${test_apks[0]}" && ! -L "${test_apks[0]}" ]] || fail 'Build the single real offline platform Android test APK before invoking this probe.'
adb -s "$ANDROID_SERIAL" install -r -t "${test_apks[0]}"
instrumentation="$(adb -s "$ANDROID_SERIAL" shell pm list instrumentation)"
instrumentation="${instrumentation//$'\r'/}"
runners=()
runner_pattern='^instrumentation:(com[.]privateassistant[.]platform([.][[:alnum:]_]+)*[.]test/androidx[.]test[.]runner[.]AndroidJUnitRunner) [(]target=(com[.]privateassistant[.]platform([.][[:alnum:]_]+)*[.]test)[)]$'
while IFS= read -r line; do
  if [[ "$line" =~ $runner_pattern ]]; then
    component="${BASH_REMATCH[1]}"
    target_package="${BASH_REMATCH[3]}"
    [[ "${component%%/*}" == "$target_package" ]] || fail 'Platform instrumentation must target its own test package.'
    runners+=("$component")
  fi
done <<< "$instrumentation"
[[ "${#runners[@]}" == 1 ]] || fail 'Expected one self-targeting platform AndroidJUnitRunner.'
test_package="${runners[0]%%/*}"
relative_model="files/model-probes/probe-$$-$RANDOM.litertlm"
adb -s "$ANDROID_SERIAL" shell run-as "$test_package" mkdir -p files/model-probes
cleanup() {
  cleanup_result=$?
  trap - EXIT
  if ! adb -s "$ANDROID_SERIAL" shell run-as "$test_package" rm -f "$relative_model" >/dev/null 2>&1; then
    printf 'Model-probe file cleanup failed; inspect the selected test target.\n' >&2
    cleanup_result=1
  fi
  exit "$cleanup_result"
}
trap cleanup EXIT
adb -s "$ANDROID_SERIAL" shell -T "run-as $test_package sh -c 'umask 077; cat > $relative_model; chmod 400 $relative_model'" < "${verified[0]}"
device_model="$(adb -s "$ANDROID_SERIAL" shell run-as "$test_package" readlink -f "$relative_model" | tr -d '\r')"
[[ "$device_model" =~ ^/data/[A-Za-z0-9_./-]+/files/model-probes/probe-[0-9]+-[0-9]+[.]litertlm$ ]] || fail 'Could not resolve a safe app-private model path.'
{
  printf 'Explicit target: %s\nAPI: %s\n' "$ANDROID_SERIAL" "$device_api"
  adb -s "$ANDROID_SERIAL" shell getprop ro.build.fingerprint
  adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abi
  adb -s "$ANDROID_SERIAL" shell getconf PAGE_SIZE
  adb -s "$ANDROID_SERIAL" shell cat /proc/meminfo | python3 -c 'import sys; print("".join(sys.stdin.readlines()[:8]), end="")'
} > "$report_directory/target.txt"
report="$report_directory/generation.txt"
[[ ! -L "$report" ]] || fail 'The generation transcript must not be a symbolic link.'
capture_ci_crash_diagnostics() {
  # This is deliberately unavailable on physical devices or other runners.
  [[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted &&
     "$ANDROID_SERIAL" == emulator-5554 && "$device_api" == 36 ]] || return 0
  [[ "$(adb -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')" == 1 ]] || return 0
  timeout 15 adb -s "$ANDROID_SERIAL" shell dumpsys activity exit-info "$test_package" \
    > "$report_directory/test-package-exit-info.txt" 2>&1 || true
  timeout 15 adb -s "$ANDROID_SERIAL" shell dumpsys meminfo "$test_package" \
    > "$report_directory/test-package-memory.txt" 2>&1 || true
  local emitted_pid
  emitted_pid="$(python3 - "$report" <<'PY'
from pathlib import Path
import re, sys
pids = {m[1] for line in Path(sys.argv[1]).read_text().splitlines()
        if (m := re.fullmatch(r'INSTRUMENTATION_STATUS: modelProbePid=([1-9][0-9]*)', line))}
if len(pids) == 1:
    print(next(iter(pids)))
PY
)"
  if [[ "$emitted_pid" =~ ^[1-9][0-9]*$ ]]; then
    # Only this synthetic test process's crash-buffer entries are read. Never global logs.
    timeout 15 adb -s "$ANDROID_SERIAL" logcat -b crash --pid="$emitted_pid" -d -v threadtime -t 200 \
      > "$report_directory/test-pid-crash.txt" 2>&1 || true
  else
    printf 'No unique synthetic test PID was emitted; crash-buffer logs were not collected.\n' \
      > "$report_directory/test-pid-crash.txt"
  fi
}
set +e
timeout --foreground 900s adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class com.privateassistant.platform.inference.LocalInferenceInstrumentedTest \
  -e runModelGeneration true -e modelLicenseAccepted true \
  -e modelId "${verified[1]}" -e modelPath "$device_model" \
  -e modelSha256 "${verified[2]}" -e modelByteCount "${verified[3]}" \
  -e runtimeVersion "${verified[4]}" "${runners[0]}" | tr -d '\r' | tee "$report"
pipeline_status=("${PIPESTATUS[@]}")
set -e
for status in "${pipeline_status[@]}"; do
  if [[ "$status" != 0 ]]; then
    capture_ci_crash_diagnostics
    fail 'Model instrumentation failed or timed out; no model result is claimed.'
  fi
done
if python3 - "$report" <<'PY'
from pathlib import Path
import json, re, sys
p = Path(sys.argv[1]); lines = p.read_text().splitlines()
codes = [int(m[1]) for line in lines if (m := re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line))]
counts = [int(m[1]) for line in lines if (m := re.fullmatch(r'INSTRUMENTATION_STATUS: numtests=(\d+)', line))]
required = {
    'INSTRUMENTATION_STATUS: class=com.privateassistant.platform.inference.LocalInferenceInstrumentedTest',
    'INSTRUMENTATION_STATUS: test=verifiedSyntheticGenerationAndCancellationReturnWithoutPlaintextFiles',
    'INSTRUMENTATION_STATUS: modelProbeScope=REAL_SYNTHETIC_GENERATION_AND_CANCELLATION',
    'OK (1 test)', 'INSTRUMENTATION_CODE: -1',
}
errors = ('INSTRUMENTATION_FAILED:', 'INSTRUMENTATION_ABORTED:',
          'INSTRUMENTATION_RESULT: shortMsg=', 'INSTRUMENTATION_STATUS: Error=')
if (not required.issubset(lines) or not counts or any(n != 1 for n in counts)
        or codes.count(1) != 1 or codes.count(0) != 1 or any(c not in (0, 1, 2) for c in codes)
        or any(line == 'FAILURES!!!' or line.startswith(errors) for line in lines)):
    raise SystemExit('Expected exactly one freshly executed, non-skipped passing real-model test')
metrics = {}
for line in lines:
    if m := re.fullmatch(r'INSTRUMENTATION_STATUS: (generationElapsedMillis|cancellationOperationMillis|generatedUtf16|nativeCancellationCalls|nativeSendFailures)=(\d+)', line):
        metrics[m[1]] = int(m[2])
if len(metrics) != 5 or any(metrics[key] <= 0 for key in ('generatedUtf16', 'nativeCancellationCalls', 'nativeSendFailures')):
    raise SystemExit('The actual generation/cancellation metrics were not reported')
(p.parent / 'result.json').write_text(json.dumps({'schemaVersion': 1, 'executed': 1, 'failures': 0, 'skipped': 0,
    'metrics': metrics, 'scope': 'Synthetic CPU generation, cancellation requested after initialization during the synchronous send window, and narrow file-canary scan. No phone latency, language/QA quality, complete privacy, traffic or hard-timeout claim.'}, indent=2) + '\n')
print('Real-model probe PASS: one actual non-skipped test; synthetic generation and cancellation observation only')
PY
then
  :
else
  capture_ci_crash_diagnostics
  fail 'The real-model result verifier rejected execution; scoped CI diagnostics were retained.'
fi
