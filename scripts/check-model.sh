#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
catalog="$project_root/config/models.json"
[[ -f "$catalog" ]] || { printf 'Missing reviewed model catalogue config/models.json. Model checking is unavailable.\n' >&2; exit 3; }
if ! python3 - "$catalog" <<'PY'
import json, sys
catalog = json.load(open(sys.argv[1]))
if not catalog.get('models'):
    raise SystemExit('No approved model is registered. Add a real immutable identity, official hash, licence, and runtime compatibility evidence before model testing; no model test has passed.')
PY
then
  exit 3
fi
if [[ $# -ne 3 || "$3" != --accept-license ]]; then
  printf 'Usage: ANDROID_SERIAL=<explicit-target> scripts/check-model.sh <catalog-model-id> <local-model-file> --accept-license\nThis uploads only the explicitly supplied verified model to the selected test target and removes that test copy on exit. Review the catalogue licence first.\n' >&2
  exit 2
fi
[[ -n "${ANDROID_SERIAL:-}" ]] || { printf 'Set ANDROID_SERIAL to the explicit authorized test device.\n' >&2; exit 2; }
command -v adb >/dev/null || { printf 'adb is unavailable. Install SDK platform-tools.\n' >&2; exit 1; }
[[ "$(adb -s "$ANDROID_SERIAL" get-state)" == device ]] || { printf 'Selected target is not connected and authorized.\n' >&2; exit 1; }
readarray -t verified < <(python3 - "$catalog" "$1" "$2" <<'PY'
from pathlib import Path
import hashlib, json, re, sys
catalog = json.load(open(sys.argv[1]))
models = [model for model in catalog['models'] if model.get('id') == sys.argv[2]]
if len(models) != 1:
    raise SystemExit('Expected exactly one matching approved model identity.')
model = models[0]
required = ('id', 'revision', 'fileName', 'byteCount', 'sha256', 'license', 'runtimeVersion', 'format', 'contextTokens', 'minimumRamBytes', 'evaluatedLanguages', 'evaluatedTasks', 'backends')
if any(key not in model for key in required):
    raise SystemExit('Model record is incomplete; a real reviewed catalogue entry is required.')
if not re.fullmatch('[0-9a-fA-F]{64}', model['sha256']):
    raise SystemExit('Model record lacks a valid SHA-256.')
license = model['license']
if not license.get('identifier') or not license.get('termsUrl', '').startswith('https://'):
    raise SystemExit('Model licence identifier and HTTPS terms URL are required.')
if model['format'] != catalog['runtime']['format'] or model['runtimeVersion'] != catalog['runtime']['version']:
    raise SystemExit('Model/runtime format or version mismatch.')
if model['format'] != 'litertlm':
    raise SystemExit('This device probe supports only the real LiteRT-LM artifact format.')
path = Path(sys.argv[3]).resolve(strict=True)
if not path.is_file() or path.stat().st_size != model['byteCount']:
    raise SystemExit('Model file byte count does not match the catalogue.')
digest = hashlib.sha256()
with path.open('rb') as source:
    for block in iter(lambda: source.read(1024 * 1024), b''):
        digest.update(block)
if digest.hexdigest().lower() != model['sha256'].lower():
    raise SystemExit('Model SHA-256 does not match the catalogue. Nothing was loaded.')
print(path)
print(model['sha256'])
print(model['byteCount'])
print(model['runtimeVersion'])
PY
)
[[ "${#verified[@]}" == 4 ]] || { printf 'Model verification failed; no device model load was attempted.\n' >&2; exit 1; }
test_package="com.privateassistant.platform.test"
relative_model="files/model-probes/probe-$$-$RANDOM.litertlm"
device_model="/data/user/0/$test_package/$relative_model"
./gradlew --no-daemon --console=plain "-Pandroid.injected.device.serial=$ANDROID_SERIAL" :platform:installOfflineDebugAndroidTest
adb -s "$ANDROID_SERIAL" shell "run-as $test_package mkdir -p files/model-probes"
trap 'adb -s "$ANDROID_SERIAL" shell "run-as $test_package rm -f $relative_model" >/dev/null 2>&1 || true' EXIT
adb -s "$ANDROID_SERIAL" shell -T "run-as $test_package sh -c 'cat > $relative_model'" < "${verified[0]}"
probe_started="$(mktemp)"
trap 'rm -f "$probe_started"; adb -s "$ANDROID_SERIAL" shell "run-as $test_package rm -f $relative_model" >/dev/null 2>&1 || true' EXIT
./gradlew --no-daemon --console=plain \
  "-Pandroid.injected.device.serial=$ANDROID_SERIAL" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privateassistant.platform.inference.LiteRtLmM0InstrumentedTest#modelLoadProbeRequiresExplicitVerifiedModel \
  "-Pandroid.testInstrumentationRunnerArguments.modelPath=$device_model" \
  "-Pandroid.testInstrumentationRunnerArguments.modelSha256=${verified[1]}" \
  "-Pandroid.testInstrumentationRunnerArguments.modelByteCount=${verified[2]}" \
  "-Pandroid.testInstrumentationRunnerArguments.runtimeVersion=${verified[3]}" \
  -Pandroid.testInstrumentationRunnerArguments.modelLicenseAccepted=true \
  :platform:connectedOfflineDebugAndroidTest
python3 - "$probe_started" "$project_root/platform/build/outputs/androidTest-results/connected" <<'PY'
from pathlib import Path
import sys, xml.etree.ElementTree as ET
started = Path(sys.argv[1]).stat().st_mtime_ns
found = []
for path in Path(sys.argv[2]).rglob('*.xml'):
    if path.stat().st_mtime_ns < started:
        continue
    root = ET.parse(path).getroot()
    for case in root.iter('testcase'):
        if case.get('name') == 'modelLoadProbeRequiresExplicitVerifiedModel' and case.get('classname', '').endswith('LiteRtLmM0InstrumentedTest'):
            found.append(case)
if not found or any(case.find(tag) is not None for case in found for tag in ('skipped', 'failure', 'error')):
    raise SystemExit('No fresh, successful, non-skipped real runtime load result was found. Model verification did not pass.')
print(f'Confirmed {len(found)} fresh, successful, non-skipped runtime load result(s).')
PY
printf '%s\n' 'Verified model M0 load probe completed on the explicit device: engine load, manual-tool conversation creation, idle cancellation call, release.' 'This is not evidence of inference quality, active generation cancellation, latency, thermal behavior, or language support; those remain M4/device evaluation work.'
