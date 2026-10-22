#!/usr/bin/env python3
"""Run every host gate, then stage both verified development APKs for download."""

from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
STABLE_DEVELOPMENT = os.environ.get("ORG_GRADLE_PROJECT_stableDevelopment") == "true"
FLAVORS = {"offline": "com.privateassistant.app" + (".preview" if STABLE_DEVELOPMENT else ".dev"),
           "connected": "com.privateassistant.app.connected" + (".preview" if STABLE_DEVELOPMENT else ".dev")}


class StagingError(Exception):
    pass


def stable_signing_record() -> dict:
    record = json.loads((ROOT / "config/development-signing.json").read_text())
    if (not isinstance(record, dict) or set(record) != {"schemaVersion", "alias", "certificateSha256", "applicationIds"} or
        type(record["schemaVersion"]) is not int or record["schemaVersion"] != 1 or
        record["alias"] != "private-assistant-dev" or
        not isinstance(record["certificateSha256"], str) or
        not re.fullmatch(r"[0-9a-f]{64}", record["certificateSha256"]) or
        record["applicationIds"] != {"offline": "com.privateassistant.app.preview",
                                      "connected": "com.privateassistant.app.connected.preview"}):
        raise StagingError("Invalid stable development public signing record")
    return record


def regular_file(path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise StagingError(f"Required regular file missing: {path.relative_to(ROOT)}")


def run_report(command: list[str], report: Path) -> str:
    with report.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding="utf-8", errors="replace")
        assert process.stdout is not None
        for line in process.stdout:
            log.write(line)
            print(line, end="", flush=True)
        if process.wait() != 0:
            raise StagingError(f"Required check failed: {report.stem}")
    return report.read_text(encoding="utf-8")


def metadata(text: str, flavor: str, minimum: int, target: int) -> dict:
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'$", text, re.M)
    target_sdk = re.search(r"^targetSdkVersion:'(\d+)'$", text, re.M)
    if package is None or sdk is None or target_sdk is None:
        raise StagingError(f"{flavor}: aapt did not return complete package/version/SDK metadata")
    application_id, code, version = package.groups()
    if application_id != FLAVORS[flavor] or int(code) < 1:
        raise StagingError(f"{flavor}: unexpected application ID or version code")
    if int(sdk[1]) != minimum or int(target_sdk[1]) != target:
        raise StagingError(f"{flavor}: unexpected minimum/target SDK")
    if not re.search(r"^application-debuggable(?:\s|$)", text, re.M):
        raise StagingError(f"{flavor}: this workflow stages development debug APKs only")
    if len(version) > 128 or any(ord(c) < 32 for c in version):
        raise StagingError(f"{flavor}: invalid version name")
    return {"applicationId": application_id, "flavor": flavor, "buildType": "debug",
            "versionCode": int(code), "versionName": version, "minSdk": minimum, "targetSdk": target}


def signing_metadata(text: str, flavor: str) -> dict:
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", text, re.M)
    schemes = re.findall(r"^Verified using (v[\d.]+) scheme[^\n]*: true$", text, re.M)
    if not digests or not any(s in {"v2", "v3", "v3.1"} for s in schemes):
        raise StagingError(f"{flavor}: missing verified modern signature or signer certificate SHA-256")
    if STABLE_DEVELOPMENT and {value.lower() for value in digests} != {stable_signing_record()["certificateSha256"]}:
        raise StagingError(f"{flavor}: APK signer differs from the pinned stable development certificate")
    return {"verified": True, "schemes": sorted(set(schemes)),
            "signerCertificateSha256": sorted({value.lower() for value in digests})}


def git_commit() -> str | None:
    result = subprocess.run(["git", "rev-parse", "--verify", "HEAD"], cwd=ROOT,
                            text=True, capture_output=True, check=False)
    value = result.stdout.strip()
    return value if result.returncode == 0 and re.fullmatch(r"[a-f0-9]{40,64}", value) else None


def stage() -> Path:
    if STABLE_DEVELOPMENT:
        stable_signing_record()
        path = os.environ.get("ANDROID_DEV_KEYSTORE_PATH", "")
        if not path or not Path(path).is_file() or not os.environ.get("ANDROID_DEV_STORE_PASSWORD"):
            raise StagingError("Stable signing credentials are missing; no fallback signing is allowed")
    artifacts = ROOT / "artifacts"
    if artifacts.is_symlink():
        raise StagingError("artifacts must not be a symlink")
    artifacts.mkdir(exist_ok=True)
    destination = artifacts / "apk"
    if destination.is_symlink() or (destination.exists() and not destination.is_dir()):
        raise StagingError("artifacts/apk must be a real directory")
    # Never leave a previous successful delivery at the advertised path after a failed attempt.
    if destination.exists():
        shutil.rmtree(destination)
    with tempfile.TemporaryDirectory(prefix=".apk-staging-", dir=artifacts) as temporary:
        staging = Path(temporary) / "apk"
        reports = staging / "reports"
        reports.mkdir(parents=True)
        run_report(["bash", str(ROOT / "scripts/check-host.sh")], reports / "host-check.txt")
        config = json.loads((ROOT / "config/toolchain.json").read_text())
        sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or ROOT / ".tooling/android-sdk")
        build_tools = sdk / "build-tools" / config["sdk"]["buildTools"]
        tools = {name: build_tools / name for name in ("aapt", "apksigner", "zipalign")}
        for name, path in tools.items():
            if not path.is_file() or not os.access(path, os.X_OK):
                raise StagingError(f"Pinned SDK build-tools {config['sdk']['buildTools']} lacks executable {name}")
        dependency_report = ROOT / "app/build/reports/dependencies/production-runtime-components.txt"
        regular_file(dependency_report)
        shutil.copyfile(dependency_report, reports / dependency_report.name)
        entries = []
        for flavor in FLAVORS:
            source = ROOT / f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk"
            regular_file(source)
            apk = staging / f"private-document-assistant-{flavor}-debug.apk"
            shutil.copyfile(source, apk)
            # Check the exact staged bytes, including existing manifest/native policy checks.
            run_report(["bash", str(ROOT / "scripts/verify-artifact.sh"), flavor, str(apk)],
                       reports / f"{flavor}-artifact.txt")
            with zipfile.ZipFile(apk) as archive:
                if not {"AndroidManifest.xml", "classes.dex"}.issubset(archive.namelist()):
                    raise StagingError(f"{flavor}: APK lacks compiled manifest or classes.dex")
            badging = run_report([str(tools["aapt"]), "dump", "badging", str(apk)],
                                 reports / f"{flavor}-package.txt")
            entry = metadata(badging, flavor, config["sdk"]["minimum"], config["sdk"]["target"])
            signature = run_report([str(tools["apksigner"]), "verify", "--verbose", "--print-certs",
                                    "--min-sdk-version", str(entry["minSdk"]), str(apk)],
                                   reports / f"{flavor}-signature.txt")
            entry["signature"] = signing_metadata(signature, flavor)
            run_report([str(tools["zipalign"]), "-c", "-P", "16", "-v", "4", str(apk)],
                       reports / f"{flavor}-zipalign.txt")
            digest = hashlib.sha256()
            with apk.open("rb") as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(block)
            entry.update({"file": apk.name, "bytes": apk.stat().st_size, "sha256": digest.hexdigest()})
            entries.append(entry)
        if entries[0]["versionCode"] != entries[1]["versionCode"]:
            raise StagingError("Offline and connected APK version codes differ")
        record = {"schemaVersion": 1, "createdAtUtc": datetime.now(timezone.utc).isoformat(),
                  "gitCommit": git_commit(), "buildTools": config["sdk"]["buildTools"],
                  "hostChecks": {"command": "scripts/check-host.sh", "result": "passed"},
                  "developmentBuildOnly": True,
                  "stableDevelopmentSigning": STABLE_DEVELOPMENT,
                  "deviceVerification": "Separate explicit-target check-device.sh run required; staging does not run it.",
                  "apks": entries}
        (staging / "APK_MANIFEST.json").write_text(json.dumps(record, indent=2) + "\n")
        (staging / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {item['file']}\n" for item in entries))
        (staging / "README.txt").write_text(
            "Private Document Assistant development debug APKs\n"
            "Android 12/API31+ required. Use synthetic documents only.\n"
            "Check SHA256SUMS and APK_MANIFEST.json before installation.\n"
            "Offline has no INTERNET permission; connected has a separate application ID.\n"
            "Host gates passed for these bytes. Device behavior and release readiness are separate gates.\n"
            "Debug signing keys may differ between builders; an APK from another builder may not update an existing install.\n"
            "Uninstalling can permanently lose vault access. Keep the source documents.\n")
        staging.rename(destination)
    return destination


def main() -> int:
    if len(sys.argv) != 1:
        print("Usage: python3 scripts/stage-apks.py (runs all host checks; no skip option)", file=sys.stderr)
        return 2
    try:
        destination = stage()
    except (StagingError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"APK staging FAILED: {error}. This attempt published no APK delivery.", file=sys.stderr)
        return 1
    print(f"Both verified development APKs staged: {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
