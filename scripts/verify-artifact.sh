#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -ne 2 || ( "$1" != offline && "$1" != connected ) ]]; then
  printf 'Usage: scripts/verify-artifact.sh <offline|connected> <path-to-apk>\n' >&2
  exit 2
fi
flavor="$1"
artifact="$2"
[[ -f "$artifact" ]] || { printf 'APK not found: %s\n' "$artifact" >&2; exit 1; }
sdk_directory="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$project_root/.tooling/android-sdk}}"
apkanalyzer_path="$(command -v apkanalyzer || true)"
if [[ -z "$apkanalyzer_path" ]]; then
  for candidate in "$sdk_directory"/cmdline-tools/*/bin/apkanalyzer; do
    if [[ -x "$candidate" ]]; then apkanalyzer_path="$candidate"; break; fi
  done
fi
aapt_path="$(command -v aapt || true)"
if [[ -z "$aapt_path" ]]; then
  sdk_build_tools="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sdk"]["buildTools"])' "$project_root/config/toolchain.json")"
  [[ ! -x "$sdk_directory/build-tools/$sdk_build_tools/aapt" ]] || aapt_path="$sdk_directory/build-tools/$sdk_build_tools/aapt"
fi
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
if [[ -n "$apkanalyzer_path" ]]; then
  "$apkanalyzer_path" manifest print "$artifact" > "$temporary/manifest.xml"
  python3 - "$flavor" "$temporary/manifest.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
a = '{http://schemas.android.com/apk/res/android}'
flavor, path = sys.argv[1:]
root = ET.parse(path).getroot()
permissions = {node.get(a+'name') for node in list(root.findall('uses-permission')) + list(root.findall('uses-permission-sdk-23'))}
internet = 'android.permission.INTERNET' in permissions
if internet != (flavor == 'connected'):
    raise SystemExit(f'{flavor}: unexpected INTERNET permission state: {internet}')
for permission in ('MANAGE_EXTERNAL_STORAGE', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'QUERY_ALL_PACKAGES', 'REQUEST_INSTALL_PACKAGES'):
    if 'android.permission.' + permission in permissions:
        raise SystemExit(f'Forbidden broad permission: {permission}')
application = root.find('application')
if application is None:
    raise SystemExit('Missing application manifest element')
for attr in ('allowBackup', 'usesCleartextTraffic'):
    if application.get(a+attr) != 'false':
        raise SystemExit(f'android:{attr} must be explicitly false in merged APK manifest')
if not application.get(a+'dataExtractionRules'):
    raise SystemExit('Missing data extraction/transfer rules in merged APK manifest')
sdk = root.find('uses-sdk')
if sdk is None or sdk.get(a+'minSdkVersion') != '31' or sdk.get(a+'targetSdkVersion') != '35':
    raise SystemExit('Expected minSdk31 and targetSdk35')
print(f'{flavor} merged manifest verified; permissions: ' + ', '.join(sorted(permissions)))
PY
elif [[ -n "$aapt_path" ]]; then
  "$aapt_path" dump permissions "$artifact" > "$temporary/permissions.txt"
  "$aapt_path" dump xmltree "$artifact" AndroidManifest.xml > "$temporary/manifest-tree.txt"
  "$aapt_path" dump badging "$artifact" > "$temporary/badging.txt"
  python3 - "$flavor" "$temporary" <<'PY'
from pathlib import Path
import re, sys
flavor, directory = sys.argv[1], Path(sys.argv[2])
text = (directory / 'permissions.txt').read_text()
permissions = set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", text))
internet = 'android.permission.INTERNET' in permissions
if internet != (flavor == 'connected'):
    raise SystemExit(f'{flavor}: unexpected INTERNET permission state: {internet}')
for permission in ('MANAGE_EXTERNAL_STORAGE', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'QUERY_ALL_PACKAGES', 'REQUEST_INSTALL_PACKAGES'):
    if 'android.permission.' + permission in permissions:
        raise SystemExit(f'Forbidden broad permission: {permission}')
tree = (directory / 'manifest-tree.txt').read_text()
for attr in ('allowBackup', 'usesCleartextTraffic'):
    if not re.search(r'android:' + attr + r'\([^\n]+\)=\(type 0x12\)0x0(?:\s|$)', tree):
        raise SystemExit(f'android:{attr} is missing or not false in merged APK manifest')
if 'android:dataExtractionRules(' not in tree:
    raise SystemExit('Missing data extraction/transfer rules in merged APK manifest')
badging = (directory / 'badging.txt').read_text()
if "sdkVersion:'31'" not in badging or "targetSdkVersion:'35'" not in badging:
    raise SystemExit('Expected minSdk31 and targetSdk35')
print(f'{flavor} merged manifest verified with aapt; permissions: ' + ', '.join(sorted(permissions)))
PY
else
  printf 'Manifest verification requires SDK apkanalyzer or aapt. Run scripts/bootstrap-android.sh and source .tooling/env.sh.\n' >&2
  exit 1
fi
python3 "$project_root/scripts/check-native-libraries.py" "$artifact"
python3 - "$artifact" <<'PY'
import hashlib, pathlib, sys
path = pathlib.Path(sys.argv[1])
digest = hashlib.sha256()
with path.open('rb') as source:
    for block in iter(lambda: source.read(1024 * 1024), b''):
        digest.update(block)
print(f'SHA-256 {digest.hexdigest()}  {path} ({path.stat().st_size} bytes)')
PY
printf '%s\n' 'APK manifest/native static checks/checksum verified. Device behavior, signing provenance, full dependency licences and release readiness require separate evidence.'
