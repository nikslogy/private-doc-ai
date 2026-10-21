#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
python3 scripts/test-fixtures.py
python3 scripts/test-native-libraries.py
python3 scripts/test-validation.py
python3 scripts/test-java-tool-env.py
python3 scripts/test-apk-delivery.py
python3 scripts/security-check.py
scripts/bootstrap-android.sh --check-only
[[ -x ./gradlew ]] || { printf 'Gradle wrapper is missing. Restore the verified wrapper files; do not fetch an unverified JAR.\n' >&2; exit 1; }
./gradlew --no-daemon --console=plain \
  :core:test \
  :platform:testOfflineDebugUnitTest :platform:testConnectedDebugUnitTest \
  :platform:lintOfflineDebug :platform:lintConnectedDebug \
  :app:verifyProductionDependencies \
  :app:testOfflineDebugUnitTest :app:testConnectedDebugUnitTest \
  :app:lintOfflineDebug :app:lintConnectedDebug \
  :app:assembleOfflineDebug :app:assembleConnectedDebug
scripts/verify-artifact.sh offline app/build/outputs/apk/offline/debug/app-offline-debug.apk
scripts/verify-artifact.sh connected app/build/outputs/apk/connected/debug/app-connected-debug.apk
printf '%s\n' 'Host checks passed. Hardware, model inference, OCR quality, recovery, and traffic inspection still require separate evidence.'
