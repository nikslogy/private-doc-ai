#!/usr/bin/env bash
# Ephemeral CI-only synthetic regression target; never connects to an unselected phone.
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
[[ "${GITHUB_ACTIONS:-}" == true ]] || { printf 'This provisioning script is for the ephemeral GitHub runner only.\n' >&2; exit 2; }
source .tooling/env.sh
[[ -r /dev/kvm && -w /dev/kvm ]] || { printf 'KVM is absent or inaccessible; no emulator test pass is claimed.\n' >&2; exit 1; }
sdk_directory="${ANDROID_SDK_ROOT:?Pinned Android bootstrap must run first}"
tools_version="$(python3 -c 'import json; print(json.load(open("config/toolchain.json"))["commandLineTools"]["version"])')"
sdkmanager="$sdk_directory/cmdline-tools/$tools_version/bin/sdkmanager"
avdmanager="$sdk_directory/cmdline-tools/$tools_version/bin/avdmanager"
[[ -x "$sdkmanager" && -x "$avdmanager" ]] || { printf 'Verified command-line tools are unavailable.\n' >&2; exit 1; }
image_package='system-images;android-36;google_apis;x86_64'
# SDKmanager resolves official package metadata and verifies its published archive checksum.
# Emulator/image revisions are rolling SDK packages; retain resolved versions, never invent hashes.
timeout 600 "$sdkmanager" --sdk_root="$sdk_directory" 'emulator' "$image_package"
report_directory="$project_root/artifacts/unlock-regression"
mkdir -p "$report_directory"
cp "$sdk_directory/emulator/source.properties" "$report_directory/emulator-source.properties"
cp "$sdk_directory/system-images/android-36/google_apis/x86_64/source.properties" "$report_directory/system-image-source.properties"
export ANDROID_AVD_HOME="$project_root/.tooling/ci-unlock-avd"
mkdir -p "$ANDROID_AVD_HOME"
avd_name='private-document-assistant-unlock-api36'
"$avdmanager" create avd --name "$avd_name" --package "$image_package" --force <<<'no'
export ANDROID_SERIAL='emulator-5554'
"$sdk_directory/emulator/emulator" -avd "$avd_name" -port 5554 \
  -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
  -camera-back none -camera-front none -memory 3072 -cores 2 -accel on \
  > "$report_directory/emulator.txt" 2>&1 &
emulator_pid=$!
cleanup() {
  adb -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
  kill "$emulator_pid" >/dev/null 2>&1 || true
  wait "$emulator_pid" 2>/dev/null || true
}
trap cleanup EXIT
boot_deadline=$((SECONDS + 360))
boot_complete=false
while (( SECONDS < boot_deadline )); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    printf 'Emulator exited before boot; inspect emulator.txt.\n' >&2
    exit 1
  fi
  if [[ "$(timeout 5 adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == 1 ]]; then
    boot_complete=true
    break
  fi
  sleep 2
done
[[ "$boot_complete" == true ]] || { printf 'Emulator boot timed out; no test pass is claimed.\n' >&2; exit 1; }
{
  printf 'Explicit target: %s\nImage package: %s\n' "$ANDROID_SERIAL" "$image_package"
  adb -s "$ANDROID_SERIAL" shell getprop ro.build.fingerprint
  adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
  adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abi
  adb -s "$ANDROID_SERIAL" shell getconf PAGE_SIZE
} > "$report_directory/target.txt"
timeout 900 scripts/check-unlock-regression.sh
