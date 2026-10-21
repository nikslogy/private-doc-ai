#!/usr/bin/env bash
# Source this file; only JVM routing is added, preserving inherited trust/options.
_private_document_java_environment() {
  local project_directory proxy_options
  project_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" || return
  proxy_options="$(python3 "$project_directory/scripts/java-tool-env.py")" || return
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$project_directory/.tooling/gradle-user-home}"
  export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$project_directory/.tooling/android-user-home}"
  mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME" || return
  if [[ -n "$proxy_options" && " ${JAVA_TOOL_OPTIONS:-} " != *" $proxy_options "* ]]; then
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }$proxy_options"
  fi
}
_private_document_java_environment
