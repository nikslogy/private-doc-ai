#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
accept_licenses=false
check_only=false
for argument in "$@"; do
  case "$argument" in
    --accept-sdk-licenses) accept_licenses=true ;;
    --check-only) check_only=true ;;
    --help) printf '%s\n' 'Usage: scripts/bootstrap-android.sh [--check-only] [--accept-sdk-licenses]' 'Downloads require verified hashes in config/toolchain.json. SDK licence acceptance is explicit.'; exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$argument" >&2; exit 2 ;;
  esac
done
java_executable=java
if [[ -n "${JAVA_HOME:-}" ]]; then java_executable="$JAVA_HOME/bin/java"; fi
for executable in "$java_executable" python3; do
  command -v "$executable" >/dev/null || { printf 'Missing %s. Install a JDK >=17 and Python >=3.10, then retry.\n' "$executable" >&2; exit 1; }
done
# Java does not automatically use HTTP_PROXY/HTTPS_PROXY as curl does.
# Keep all inherited trust/header options and all tool caches inside writable
# workspace defaults. This also validates unsupported proxy URLs before tools run.
source "$project_root/scripts/java-tool-env.sh"
java_spec="$("$java_executable" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p')"
if [[ ! "$java_spec" =~ ^[0-9]+$ ]] || (( java_spec < 17 )); then
  printf 'A JDK >=17 is required; found Java %s. Bytecode remains Java 17.\n' "$java_spec" >&2
  exit 1
fi
config="$project_root/config/toolchain.json"
[[ -f "$config" ]] || { printf 'Missing %s; obtain the reviewed pinned toolchain configuration.\n' "$config" >&2; exit 1; }
readarray -t configured < <(python3 - "$config" <<'PY'
import json, re, sys
from urllib.parse import urlsplit
c = json.load(open(sys.argv[1]))
for component in ('gradle', 'commandLineTools'):
    item = c[component]
    if not re.fullmatch('[0-9a-fA-F]{64}', item['sha256']):
        raise SystemExit(f'{component}: official SHA-256 missing; no unverified download is permitted')
    url = urlsplit(item['url'])
    if url.scheme != 'https' or url.hostname not in ('services.gradle.org', 'downloads.gradle.org', 'dl.google.com'):
        raise SystemExit(f'{component}: expected an official HTTPS download URL')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', str(item['version'])):
        raise SystemExit(f'{component}: invalid version')
    for field in ('version', 'url', 'sha256'):
        print(item[field])
print(c['sdk']['compile'])
print(c['sdk']['buildTools'])
wrapper = c['gradleWrapper']
if not re.fullmatch('[0-9a-fA-F]{64}', wrapper['sha256']):
    raise SystemExit('gradleWrapper: pinned official SHA-256 is required')
version = c['gradle']['version']
tag = version if version.count('.') >= 2 else version + '.0'
expected_wrapper_url = f'https://raw.githubusercontent.com/gradle/gradle/v{tag}/gradle/wrapper/gradle-wrapper.jar'
if wrapper['url'] != expected_wrapper_url:
    raise SystemExit('gradleWrapper: URL must identify the exact official Gradle GitHub tag matching the pinned distribution')
print(wrapper['url'])
print(wrapper['sha256'])
PY
)
[[ "${#configured[@]}" == 10 ]] || { printf 'Toolchain configuration is incomplete or invalid.\n' >&2; exit 1; }
gradle_version="${configured[0]}"
tools_version="${configured[3]}"
sdk_compile="${configured[6]}"
sdk_build_tools="${configured[7]}"
tooling="$project_root/.tooling"
sdk_directory="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$tooling/android-sdk}}"
gradle_executable="$tooling/gradle-$gradle_version/bin/gradle"
sdkmanager="$sdk_directory/cmdline-tools/$tools_version/bin/sdkmanager"
if [[ "$check_only" == true ]]; then
  missing=()
  wrapper_jar="$project_root/gradle/wrapper/gradle-wrapper.jar"
  [[ -x "$project_root/gradlew" ]] || missing+=("Repository Gradle launcher is missing or not executable: $project_root/gradlew")
  if [[ -f "$wrapper_jar" ]]; then
    if ! python3 - "${configured[9]}" "$wrapper_jar" <<'PY'
import hashlib, pathlib, sys
actual = hashlib.sha256(pathlib.Path(sys.argv[2]).read_bytes()).hexdigest()
if actual.lower() != sys.argv[1].lower():
    raise SystemExit('Gradle wrapper JAR SHA-256 mismatch; do not execute it.')
PY
    then
      missing+=("Official Gradle wrapper JAR failed its configured SHA-256 check")
    fi
  elif [[ ! -x "$gradle_executable" ]]; then
    missing+=("Gradle is unavailable: neither the verified wrapper JAR nor $gradle_executable exists")
  fi
  [[ -f "$sdk_directory/platforms/android-$sdk_compile/android.jar" ]] || missing+=("Missing SDK platform: $sdk_directory/platforms/android-$sdk_compile/android.jar")
  [[ -x "$sdk_directory/build-tools/$sdk_build_tools/aapt" ]] || missing+=("Missing SDK build-tools: $sdk_directory/build-tools/$sdk_build_tools/aapt")
  if (( ${#missing[@]} > 0 )); then
    printf 'Missing or invalid toolchain prerequisites:\n' >&2
    printf '  - %s\n' "${missing[@]}" >&2
    printf 'Run scripts/bootstrap-android.sh with access to the configured official downloads, or provision the verified archives locally. SDK packages: platforms;android-%s and build-tools;%s.\n' "$sdk_compile" "$sdk_build_tools" >&2
    exit 1
  fi
  printf 'Toolchain launch prerequisites present: Java %s, verified Gradle wrapper or installed distribution, SDK %s, build-tools %s.\n' "$java_spec" "$sdk_compile" "$sdk_build_tools"
  [[ ! -f "$wrapper_jar" || -x "$gradle_executable" ]] || printf '%s\n' 'The official wrapper may still need to download its checksum-pinned Gradle distribution on first use.'
  exit 0
fi
for executable in curl; do
  command -v "$executable" >/dev/null || { printf 'Install %s to download official archives, or provide the pinned local toolchain.\n' "$executable" >&2; exit 1; }
done
python3 - "$project_root" <<'PY'
import shutil, sys
if shutil.disk_usage(sys.argv[1]).free < 4 * 1024**3:
    raise SystemExit('At least 4 GiB of free disk is required for the SDK, Gradle, and build outputs.')
try:
    memory = int(next(line.split()[1] for line in open('/proc/meminfo') if line.startswith('MemTotal:'))) * 1024
    if memory < 2 * 1024**3:
        raise SystemExit('At least 2 GiB of host RAM is required; 4 GiB or more is recommended.')
except FileNotFoundError:
    pass
PY
mkdir -p "$tooling/downloads"
download() {
  local url="$1" expected="$2" destination="$3"
  if [[ ! -f "$destination" ]]; then
    printf 'Downloading %s\n' "$url"
    if ! curl --fail --location --retry 2 --connect-timeout 15 --max-time 600 --output "$destination.part" "$url"; then
      rm -f "$destination.part"
      printf 'Download failed. Check the session network/proxy policy and allow the official host; alternatively place the verified archive at %s.\n' "$destination" >&2
      return 1
    fi
    mv "$destination.part" "$destination"
  fi
  python3 - "$expected" "$destination" <<'PY'
import hashlib, pathlib, sys
path = pathlib.Path(sys.argv[2])
actual = hashlib.sha256(path.read_bytes()).hexdigest()
if actual.lower() != sys.argv[1].lower():
    raise SystemExit(f'SHA-256 mismatch for {path}; discard it and re-fetch from the official source. Expected {sys.argv[1]}, got {actual}.')
print(f'Verified SHA-256: {path.name}')
PY
}
extract() {
  python3 - "$1" "$2" <<'PY'
from pathlib import Path
import sys, zipfile
destination = Path(sys.argv[2]).resolve()
with zipfile.ZipFile(sys.argv[1]) as archive:
    if sum(item.file_size for item in archive.infolist()) > 2 * 1024**3:
        raise SystemExit('Archive exceeds the 2 GiB extraction limit.')
    for item in archive.infolist():
        path = (destination / item.filename).resolve()
        if not path.is_relative_to(destination):
            raise SystemExit('Archive path traversal rejected.')
        if (item.external_attr >> 16) & 0o170000 == 0o120000:
            raise SystemExit('Archive symlink rejected.')
    archive.extractall(destination)
    for item in archive.infolist():
        mode = (item.external_attr >> 16) & 0o777
        if mode:
            (destination / item.filename).chmod(mode)
PY
}
mkdir -p "$project_root/gradle/wrapper"
download "${configured[8]}" "${configured[9]}" "$project_root/gradle/wrapper/gradle-wrapper.jar"
if [[ ! -x "$gradle_executable" ]]; then
  archive="$tooling/downloads/gradle-$gradle_version-bin.zip"
  download "${configured[1]}" "${configured[2]}" "$archive"
  extract "$archive" "$tooling"
fi
if [[ ! -x "$sdkmanager" ]]; then
  archive="$tooling/downloads/commandlinetools-$tools_version.zip"
  download "${configured[4]}" "${configured[5]}" "$archive"
  staging="$(mktemp -d "$tooling/sdk-extract.XXXXXX")"
  extract "$archive" "$staging"
  mkdir -p "$sdk_directory/cmdline-tools"
  mv "$staging/cmdline-tools" "$sdk_directory/cmdline-tools/$tools_version"
  rmdir "$staging"
  sdkmanager="$sdk_directory/cmdline-tools/$tools_version/bin/sdkmanager"
fi
if [[ "$accept_licenses" == true ]]; then
  # sdkmanager may stop reading after the final licence; avoid SIGPIPE hiding its status.
  license_answers="$(mktemp "$tooling/license-answers.XXXXXX")"
  python3 - "$license_answers" <<'PY'
import pathlib, sys
pathlib.Path(sys.argv[1]).write_text('y\n' * 100)
PY
  "$sdkmanager" --sdk_root="$sdk_directory" --licenses < "$license_answers"
  rm -f "$license_answers"
fi
"$sdkmanager" --sdk_root="$sdk_directory" "platform-tools" "platforms;android-$sdk_compile" "build-tools;$sdk_build_tools" || {
  printf 'SDK setup failed. Review network access and SDK licences; rerun with --accept-sdk-licenses only after accepting those terms.\n' >&2
  exit 1
}
python3 - "$project_root" "$sdk_directory" "$gradle_version" "$sdkmanager" <<'PY'
from pathlib import Path
import os, shlex, sys
root, sdk = Path(sys.argv[1]), Path(sys.argv[2]).resolve()
properties = root / 'local.properties'
lines = properties.read_text().splitlines() if properties.exists() else []
lines = [line for line in lines if not line.startswith('sdk.dir=')]
escaped = str(sdk).replace('\\', '\\\\').replace(':', '\\:').replace(' ', '\\ ')
properties.write_text('\n'.join(lines + [f'sdk.dir={escaped}']) + '\n')
environment = root / '.tooling' / 'env.sh'
environment.write_text(f'export ANDROID_SDK_ROOT={shlex.quote(str(sdk))}\nexport ANDROID_HOME={shlex.quote(str(sdk))}\n' +
    f'if [[ -z "${{GRADLE_USER_HOME:-}}" ]]; then export GRADLE_USER_HOME={shlex.quote(os.environ["GRADLE_USER_HOME"])}; fi\n' +
    f'if [[ -z "${{ANDROID_USER_HOME:-}}" ]]; then export ANDROID_USER_HOME={shlex.quote(os.environ["ANDROID_USER_HOME"])}; fi\n' +
    # Source at use time: never persist inherited JVM headers/options/secrets.
    f'source {shlex.quote(str(root / "scripts/java-tool-env.sh"))} || return $?\n' +
    f'export PATH={shlex.quote(str(root / ".tooling" / ("gradle-" + sys.argv[3]) / "bin"))}:' +
    f'{shlex.quote(str(sdk / "platform-tools"))}:{shlex.quote(str(Path(sys.argv[4]).parent))}:"$PATH"\n')
PY
printf '%s\n' 'Android setup complete. Run: source .tooling/env.sh' 'Then run scripts/check-host.sh. Model weights are never downloaded by bootstrap.'
