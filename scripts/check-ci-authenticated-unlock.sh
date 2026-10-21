#!/usr/bin/env bash
# Real credential/Keystore probe on a newly provisioned, disposable GitHub emulator.
# The PIN below is a public synthetic test fixture, never a production fallback.
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] ||
  fail 'This probe requires a disposable GitHub-hosted CI runner.'
[[ "${ANDROID_SERIAL:-}" == emulator-5554 ]] ||
  fail 'This CI-only probe requires the explicit ANDROID_SERIAL=emulator-5554 target.'
for command in adb timeout python3 tee tr; do
  command -v "$command" >/dev/null || fail "Required command unavailable: $command"
done
[[ "$(adb -s "$ANDROID_SERIAL" get-state)" == device ]] || fail 'The explicit emulator is unavailable.'
device_api="$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
device_qemu="$(adb -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')"
[[ "$device_api" == 36 && "$device_qemu" == 1 ]] ||
  fail 'The selected target must be the provisioned API36 QEMU emulator.'

report_directory="$project_root/artifacts/unlock-regression"
[[ ! -L "$project_root/artifacts" && ! -L "$report_directory" ]] || fail 'Report paths must not be symlinks.'
mkdir -p "$report_directory"

# Never replace an existing credential. On a fresh emulator, verifying without
# --old succeeds; an existing secure lock causes this command to fail.
initial_credential="$(adb -s "$ANDROID_SERIAL" shell locksettings verify --user 0)" ||
  fail 'The emulator already has a credential or cannot verify its fresh state; no credential was changed.'
initial_credential="${initial_credential//$'\r'/}"
[[ "$initial_credential" == 'Lock credential verified successfully' ]] ||
  fail 'Fresh credential state could not be confirmed; no credential was changed.'
synthetic_pin=135790
adb -s "$ANDROID_SERIAL" shell locksettings set-pin --user 0 "$synthetic_pin"

for flavor in offline connected; do
  # Android library instrumentation is a self-targeting APK. Only accept one
  # flavor-specific artifact in its expected AGP output directory.
  shopt -s nullglob
  test_apks=("$project_root/platform/build/outputs/apk/androidTest/$flavor/debug/"*"-$flavor-debug-androidTest.apk")
  shopt -u nullglob
  [[ "${#test_apks[@]}" == 1 && -f "${test_apks[0]}" && ! -L "${test_apks[0]}" ]] ||
    fail "Expected one real $flavor platform instrumentation APK."
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
  [[ "${#runners[@]}" == 1 ]] || fail "Expected one installed $flavor platform AndroidJUnitRunner."

  report="$report_directory/authenticated-$flavor.txt"
  [[ ! -L "$report" ]] || fail 'The report must not be a symlink.'
  # This invokes the actual Android credential/Gatekeeper verification path.
  # Install/discovery happen before authentication to preserve the real 30s
  # production Keystore authorization window. Expiration remains a test failure.
  authenticated="$(adb -s "$ANDROID_SERIAL" shell locksettings verify --old "$synthetic_pin" --user 0)" ||
    fail "Real credential verification failed for $flavor."
  authenticated="${authenticated//$'\r'/}"
  [[ "$authenticated" == 'Lock credential verified successfully' ]] ||
    fail "Real credential verification was not confirmed for $flavor."
  printf '%s\n' "$authenticated" > "$report"
  set +e
  timeout --foreground 120s adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
    -e class com.privateassistant.platform.vault.VaultLifecycleInstrumentedTest \
    -e runAuthenticatedVaultLifecycle true "${runners[0]}" | tr -d '\r' | tee -a "$report"
  pipeline_status=("${PIPESTATUS[@]}")
  set -e
  for status in "${pipeline_status[@]}"; do
    [[ "$status" == 0 ]] || fail "Authenticated $flavor instrumentation failed or timed out; inspect $report."
  done

  # am instrument may exit zero for failed/skipped tests. Require the one named
  # lifecycle test to start and pass, a successful runner result, and no skips.
  python3 - "$report" <<'PY'
from pathlib import Path
import re
import sys

lines = Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
codes = [int(match[1]) for line in lines
         if (match := re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line))]
counts = [int(match[1]) for line in lines
          if (match := re.fullmatch(r"INSTRUMENTATION_STATUS: numtests=(\d+)", line))]
required = {
    "Lock credential verified successfully",
    "INSTRUMENTATION_STATUS: class=com.privateassistant.platform.vault.VaultLifecycleInstrumentedTest",
    "INSTRUMENTATION_STATUS: test=authenticatedOriginalDedupReopenAndInterruptedPublicationRecovery",
    "OK (1 test)",
    "INSTRUMENTATION_CODE: -1",
}
errors = ("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:",
          "INSTRUMENTATION_RESULT: shortMsg=", "INSTRUMENTATION_STATUS: Error=")
if (not required.issubset(lines) or not counts or any(count != 1 for count in counts)
        or codes.count(1) != 1 or codes.count(0) != 1
        or any(code not in (0, 1, 2) for code in codes)
        or any(line == "FAILURES!!!" or line.startswith(errors) for line in lines)):
    raise SystemExit("Authenticated lifecycle probe did not report exactly one non-skipped passing test")
print("Authenticated lifecycle probe passed: one executed test; no skipped cases")
PY
done
printf '%s\n' 'Both real-credential emulator lifecycle probes passed. Physical fingerprint/device checks remain separate.'
