#!/usr/bin/env bash
# Exercise stable Gradle configuration with a disposable synthetic signing identity.
# Never publish these APKs; verified default APK copies were already staged by CI.
set -euo pipefail
set +x
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
[[ "${GITHUB_ACTIONS:-}" == true ]] || { printf 'This synthetic signing build is CI-only.\n' >&2; exit 2; }
source .tooling/env.sh
signing_directory="$(mktemp -d "$RUNNER_TEMP/private-assistant-signing-test.XXXXXX")"
trap 'rm -rf -- "$signing_directory"' EXIT
chmod 700 "$signing_directory"
export ANDROID_DEV_KEYSTORE_PATH="$signing_directory/synthetic.p12"
export ANDROID_DEV_STORE_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
keytool -genkeypair -alias private-assistant-dev -keyalg RSA -keysize 2048 \
  -validity 2 -storetype PKCS12 -keystore "$ANDROID_DEV_KEYSTORE_PATH" \
  -storepass:env ANDROID_DEV_STORE_PASSWORD -keypass:env ANDROID_DEV_STORE_PASSWORD \
  -dname 'CN=Synthetic CI signing test' >/dev/null 2>&1
export ORG_GRADLE_PROJECT_stableDevelopment=true
./gradlew --no-daemon --console=plain :app:assembleOfflineDebug :app:assembleConnectedDebug
python3 - <<'PY'
import importlib.util
from pathlib import Path
import os, subprocess
spec = importlib.util.spec_from_file_location('staging', 'scripts/stage-apks.py')
staging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(staging)
tools = Path(os.environ['ANDROID_SDK_ROOT']) / 'build-tools/35.0.0'
for flavor, expected_label in [('offline', 'Private Vault Preview'), ('connected', 'Private Vault Connected Preview')]:
    apk = Path(f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk')
    badging = subprocess.check_output([str(tools/'aapt'), 'dump', 'badging', str(apk)], text=True)
    staging.metadata(badging, flavor, 31, 35)
    if f"application-label:'{expected_label}'" not in badging:
        raise SystemExit('Stable preview launcher label mismatch')
    signature = subprocess.check_output([str(tools/'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    try:
        staging.signing_metadata(signature, flavor)
    except staging.StagingError as failure:
        if 'APK signer differs from the pinned' not in str(failure):
            raise
    else:
        raise SystemExit('Synthetic signing identity unexpectedly passed the real delivery pin')
print('Stable mode assembled both preview IDs/labels with a real disposable key; delivery rejected its unpinned signer. No synthetic APK is published.')
PY
