#!/usr/bin/env bash
# Separate, explicit public-model experiment on a disposable CI emulator. Never a routine host check.
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
[[ $# == 2 && "$1" == --download-reviewed-model && "$2" == --accept-license ]] || {
  printf 'Explicit --download-reviewed-model --accept-license flags are required.\n' >&2; exit 2;
}
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || {
  printf 'This provisioning script requires the disposable GitHub-hosted runner.\n' >&2; exit 2;
}
source .tooling/env.sh
[[ -r /dev/kvm && -w /dev/kvm ]] || { printf 'KVM unavailable; no model test pass is claimed.\n' >&2; exit 1; }
report_directory="$project_root/artifacts/model-runtime-probe"
[[ ! -L "$project_root/artifacts" && ! -L "$report_directory" ]] || { printf 'Report paths must not be symlinks.\n' >&2; exit 1; }
mkdir -p "$report_directory"
python3 - "$report_directory/host-resources.json" <<'PY'
from pathlib import Path
import json, shutil, sys
memory = {}
for line in Path('/proc/meminfo').read_text().splitlines():
    key, value = line.split(':', 1)
    memory[key] = int(value.strip().split()[0]) * 1024
# Four GiB nominal emulator RAM plus two GiB measured host headroom is the
# explicit small-model experiment. A result is not proof for all 4 GB phones.
# No Gradle process runs during native inference.
required = (4096 + 2048) * 1024**2
free = shutil.disk_usage('.').free
Path(sys.argv[1]).write_text(json.dumps({'hostTotalBytes': memory['MemTotal'],
    'hostAvailableBytes': memory['MemAvailable'], 'minimumAvailableGuardBytes': required,
    'modelId': 'qwen3-06b-q4-nothink', 'emulatorRamMiB': 4096,
    'scope': 'Fixed small-model experiment on nominal 4 GiB x86_64 emulator; no physical-phone RAM minimum established',
    'hostFreeDiskBytes': free}, indent=2) + '\n')
if memory['MemAvailable'] < required or free < 7 * 1024**3:
    raise SystemExit('Insufficient measured runner memory/disk; no native model result is claimed')
PY
sdk_directory="${ANDROID_SDK_ROOT:?Pinned Android bootstrap must run first}"
tools_version="$(python3 -c 'import json; print(json.load(open("config/toolchain.json"))["commandLineTools"]["version"])')"
sdkmanager="$sdk_directory/cmdline-tools/$tools_version/bin/sdkmanager"
avdmanager="$sdk_directory/cmdline-tools/$tools_version/bin/avdmanager"
[[ -x "$sdkmanager" && -x "$avdmanager" ]] || { printf 'Verified SDK tools unavailable.\n' >&2; exit 1; }
image_package='system-images;android-36;google_apis;x86_64'
# Official SDKmanager verifies resolved rolling emulator/image packages; retain their actual identities.
timeout 600 "$sdkmanager" --sdk_root="$sdk_directory" emulator "$image_package"
cp "$sdk_directory/emulator/source.properties" "$report_directory/emulator-source.properties"
cp "$sdk_directory/system-images/android-36/google_apis/x86_64/source.properties" "$report_directory/system-image-source.properties"
model_directory="$(mktemp -d "$RUNNER_TEMP/private-doc-ai-public-model.XXXXXX")"
avd_directory="$(mktemp -d "$RUNNER_TEMP/private-doc-ai-model-avd.XXXXXX")"
chmod 700 "$model_directory" "$avd_directory"
export ANDROID_AVD_HOME="$avd_directory"
export ANDROID_SERIAL='emulator-5554'
emulator_pid=''
cleanup() {
  cleanup_result=$?
  trap - EXIT
  if [[ -n "$emulator_pid" ]]; then
    timeout 10 adb -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
    kill "$emulator_pid" >/dev/null 2>&1 || true
    wait "$emulator_pid" 2>/dev/null || true
  fi
  rm -rf -- "$model_directory" "$avd_directory"
  exit "$cleanup_result"
}
trap cleanup EXIT
python3 scripts/download-probe-model.py --model-id qwen3-06b-q4-nothink \
  --output "$model_directory/model.litertlm" --report "$report_directory/download-verification.json" \
  --download --accept-license
avd_name='private-document-assistant-model-api36'
"$avdmanager" create avd --name "$avd_name" --package "$image_package" --force <<<'no'
python3 - "$ANDROID_AVD_HOME/$avd_name.avd/config.ini" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1]); lines = p.read_text().splitlines()
lines = [line for line in lines if not line.startswith('disk.dataPartition.size=')]
lines.append('disk.dataPartition.size=6G')
p.write_text('\n'.join(lines) + '\n')
PY
"$sdk_directory/emulator/emulator" -avd "$avd_name" -port 5554 \
  -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
  -camera-back none -camera-front none -memory 4096 -cores 2 -accel on \
  > "$report_directory/emulator.txt" 2>&1 &
emulator_pid=$!
boot_deadline=$((SECONDS + 360))
boot_complete=false
while (( SECONDS < boot_deadline )); do
  kill -0 "$emulator_pid" 2>/dev/null || { printf 'Emulator exited before boot; no model result is claimed.\n' >&2; exit 1; }
  if [[ "$(timeout 5 adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == 1 ]]; then
    boot_complete=true; break
  fi
  sleep 2
done
[[ "$boot_complete" == true ]] || { printf 'Model emulator boot timed out; no model pass is claimed.\n' >&2; exit 1; }
[[ "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" == 36 ]] || exit 1
[[ "$(adb -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || exit 1
[[ "$(adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')" == x86_64 ]] || exit 1
timeout 1200 scripts/check-model-generation.sh qwen3-06b-q4-nothink "$model_directory/model.litertlm" --accept-license
printf '%s\n' 'Real synthetic model probe completed on the explicit emulator. Phone capability, document QA, complete privacy and hard native timeout evidence remain separate.'
