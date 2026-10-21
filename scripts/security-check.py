#!/usr/bin/env python3
"""Small fail-closed source policy checks; this is not a security audit."""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
failures: list[str] = []
FORBIDDEN_PERMISSIONS = {
    "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION", "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA", "android.permission.READ_CONTACTS",
}


def fail(path: Path, message: str) -> None:
    failures.append(f"{path.relative_to(ROOT)}: {message}")


def main() -> int:
    manifests = sorted(ROOT.glob("*/src/**/AndroidManifest.xml"))
    if not manifests:
        failures.append("No Android manifests found; source verification cannot complete.")
    for path in manifests:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            fail(path, f"invalid XML: {error}")
            continue
        connected_overlay = "connected" in path.parts and path.parts[0] != ""
        for permission in root.findall("uses-permission") + root.findall("uses-permission-sdk-23"):
            if permission.get(TOOLS + "node") == "remove":
                continue
            name = permission.get(ANDROID + "name", "")
            if name in FORBIDDEN_PERMISSIONS:
                fail(path, f"broad or unrelated permission {name}")
            if name == "android.permission.INTERNET" and not connected_overlay:
                fail(path, "INTERNET may only be declared in the connected flavor overlay")
        for application in root.findall("application"):
            if path == ROOT / "app/src/main/AndroidManifest.xml":
                for attr in ("allowBackup", "usesCleartextTraffic"):
                    if application.get(ANDROID + attr) != "false":
                        fail(path, f"android:{attr} must be explicitly false")
                if not application.get(ANDROID + "dataExtractionRules"):
                    fail(path, "API 31+ device-transfer/cloud backup exclusion rules are required")
            for component in application:
                if component.tag in {"provider", "service", "receiver"} and component.get(ANDROID + "exported") == "true":
                    fail(path, f"exported {component.tag} requires a reviewed policy exception")
    for path in sorted(ROOT.glob("app/src/**/res/xml/*.xml")):
        try:
            tree = ET.parse(path).getroot()
        except ET.ParseError as error:
            fail(path, f"invalid XML: {error}")
            continue
        if tree.tag == "paths":
            for entry in tree:
                if entry.tag in {"root-path", "external-path", "external-files-path", "external-cache-path"}:
                    fail(path, f"broad FileProvider root {entry.tag}")
                if entry.get("path", "") in {"", ".", "/"}:
                    fail(path, "FileProvider path must be a narrow export directory")
        if tree.tag == "data-extraction-rules":
            for section in ("cloud-backup", "device-transfer"):
                excludes = tree.findall(f"{section}/exclude")
                for domain in ("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"):
                    if not any(item.get("domain") == domain and item.get("path") == "." for item in excludes):
                        fail(path, f"missing full {section} exclusion for {domain}")
    forbidden_code = {
        r"\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\b": "generated shell execution",
        r"\bProcessBuilder\s*\(": "process execution",
        r"\.\s*(?:evaluateJavascript|addJavascriptInterface)\s*\(": "executable WebView interface",
        r"\b(?:println|printStackTrace)\s*\(": "unstructured logging in production source",
        r"\b(?:Log|Timber)\s*\.\s*(?:v|d|i|w|e|wtf)\s*\(": "logging requires an explicit safe-code review",
    }
    for module in ("app", "core", "platform"):
        for path in sorted((ROOT / module / "src").rglob("*")):
            source_set = path.relative_to(ROOT / module / "src").parts[0]
            if source_set.startswith(("androidTest", "test")):
                continue
            if path.suffix not in {".kt", ".java"}:
                continue
            source = path.read_text()
            if re.search(r"^import\s+com\.google\.mlkit\.", source, re.M):
                fail(path, "ML Kit diagnostics review is unresolved; SDK code belongs in device probes only")
            for expression, message in forbidden_code.items():
                if re.search(expression, source):
                    fail(path, message)
    dependency_files = list(ROOT.glob("**/build.gradle.kts")) + list(ROOT.glob("gradle/*.toml"))
    for path in dependency_files:
        if any(part in {".gradle", ".tooling", "build"} for part in path.relative_to(ROOT).parts):
            continue
        text = path.read_text()
        if path.name == "build.gradle.kts":
            for line in text.splitlines():
                if "libs.mlkit." in line and not line.strip().startswith("androidTestImplementation("):
                    fail(path, "ML Kit must not enter production dependency configurations")
        if re.search(r"[\"'](?:[^\"']*:)?(?:latest\.[^\"']*|[^\"']*SNAPSHOT|[^\"']*:\+)[\"']", text, re.I):
            fail(path, "dynamic dependency version")
    if failures:
        print("Source policy checks failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("Source policy checks passed (manifest rules, export roots, logging/execution, pinned dependency patterns).")
    print("This source check does not establish runtime privacy, cryptographic correctness, or dependency safety.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
