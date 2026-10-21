#!/usr/bin/env bash
# Repository launcher. bootstrap-android.sh installs the official, verified wrapper JAR.
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$project_root/scripts/java-tool-env.sh"
config="$project_root/config/toolchain.json"
readarray -t wrapper_config < <(python3 - "$config" <<'PY'
import json, sys
config = json.load(open(sys.argv[1]))
print(config['gradle']['version'])
print(config['gradleWrapper']['sha256'])
PY
)
[[ ${#wrapper_config[@]} == 2 ]] || { printf 'Invalid pinned toolchain configuration.\n' >&2; exit 1; }
wrapper_jar="$project_root/gradle/wrapper/gradle-wrapper.jar"
if [[ -f "$wrapper_jar" ]]; then
  python3 - "$wrapper_jar" "${wrapper_config[1]}" <<'PY'
import hashlib, pathlib, sys
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest() != sys.argv[2]:
    raise SystemExit('Gradle wrapper SHA-256 mismatch. Restore the official pinned JAR.')
PY
  java_executable=java
  if [[ -n "${JAVA_HOME:-}" ]]; then java_executable="$JAVA_HOME/bin/java"; fi
  exec "$java_executable" -classpath "$wrapper_jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi
installed_gradle="$project_root/.tooling/gradle-${wrapper_config[0]}/bin/gradle"
if [[ -x "$installed_gradle" ]]; then
  exec "$installed_gradle" -p "$project_root" "$@"
fi
printf '%s\n' 'Pinned Gradle is not installed. Run scripts/bootstrap-android.sh first.' 'The official wrapper JAR and distribution must pass the recorded SHA-256 checks.' >&2
exit 1
