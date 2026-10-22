#!/usr/bin/env python3
"""Synthetic pipeline tests with mocked SDK output; never Android/APK build evidence."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class ApkDeliveryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        shutil.copytree(ROOT / "scripts", self.root / "scripts", ignore=shutil.ignore_patterns("__pycache__"))
        (self.root / "config").mkdir()
        (self.root / "config/toolchain.json").write_text(json.dumps({
            "sdk": {"minimum": 31, "target": 35, "buildTools": "35.0.0"}}))
        self.environment = os.environ.copy()
        for name in ("ORG_GRADLE_PROJECT_stableDevelopment", "ANDROID_DEV_KEYSTORE_PATH",
                     "ANDROID_DEV_STORE_PASSWORD", "ANDROID_DEV_KEYSTORE_BASE64"):
            self.environment.pop(name, None)
        self.environment["ANDROID_SDK_ROOT"] = str(self.root / "sdk")
        self.environment["PATH"] = str(self.root / "bin") + os.pathsep + self.environment["PATH"]
        self.executable(self.root / "scripts/check-host.sh", """#!/bin/sh
printf 'Synthetic host stub only; no Gradle tasks run.\\n'
exit "${TEST_HOST_EXIT:-0}"
""")
        self.executable(self.root / "bin/apkanalyzer", f"""#!{sys.executable}
import pathlib, sys
connected = 'connected' in pathlib.Path(sys.argv[-1]).name
permission = '<uses-permission android:name="android.permission.INTERNET"/>' if connected else ''
print('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35"/>' + permission + '<application android:allowBackup="false" android:usesCleartextTraffic="false" android:dataExtractionRules="@xml/data_extraction_rules"/></manifest>')
""")
        tools = self.root / "sdk/build-tools/35.0.0"
        self.executable(tools / "aapt", f"""#!{sys.executable}
import os, pathlib, sys
connected = 'connected' in pathlib.Path(sys.argv[-1]).name
package = 'com.privateassistant.app' + ('.connected' if connected else '') + '.dev'
if os.environ.get('TEST_WRONG_ID'): package = 'example.wrong'
print("package: name='" + package + "' versionCode='2' versionName='0.1.1-dev'")
print("sdkVersion:'31'\\ntargetSdkVersion:'35'")
if not os.environ.get('TEST_NOT_DEBUG'): print('application-debuggable')
""")
        self.executable(tools / "apksigner", f"""#!{sys.executable}
import os, sys
if os.environ.get('TEST_BAD_SIGNATURE'): sys.exit(1)
print('Verified using v2 scheme (APK Signature Scheme v2): true')
if not os.environ.get('TEST_NO_CERTIFICATE'): print('Signer #1 certificate SHA-256 digest: ' + 'ab' * 32)
""")
        self.executable(tools / "zipalign", "#!/bin/sh\nexit \"${TEST_ZIPALIGN_EXIT:-0}\"\n")
        for flavor in ("offline", "connected"):
            apk = self.root / f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk"
            apk.parent.mkdir(parents=True)
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"synthetic manifest marker; SDK is mocked")
                archive.writestr("classes.dex", b"synthetic DEX marker; never installable")
        report = self.root / "app/build/reports/dependencies/production-runtime-components.txt"
        report.parent.mkdir(parents=True)
        report.write_text("synthetic dependency inventory marker\n")
        self.destination = self.root / "artifacts/apk"

    def executable(self, path: Path, text: str):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        path.chmod(0o755)

    def run_stage(self):
        return subprocess.run([sys.executable, str(self.root / "scripts/stage-apks.py")], cwd=self.root,
                              env=self.environment, capture_output=True, text=True, timeout=30)

    def assert_failed_closed(self):
        result = self.run_stage()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("APK staging FAILED", result.stderr)
        self.assertFalse(self.destination.exists())
        self.assertFalse(list((self.root / "artifacts").glob(".apk-staging-*")))

    def test_stages_both_byte_identical_copies_and_actual_digests(self):
        result = self.run_stage()
        self.assertEqual(result.returncode, 0, result.stderr)
        record = json.loads((self.destination / "APK_MANIFEST.json").read_text())
        self.assertEqual(len(record["apks"]), 2)
        for apk in record["apks"]:
            with self.subTest(flavor=apk["flavor"]):
                data = (self.destination / apk["file"]).read_bytes()
                original = self.root / f"app/build/outputs/apk/{apk['flavor']}/debug/app-{apk['flavor']}-debug.apk"
                self.assertEqual(data, original.read_bytes())
                self.assertEqual(apk["sha256"], hashlib.sha256(data).hexdigest())
                self.assertIn(apk["sha256"] + "  " + apk["file"], (self.destination / "SHA256SUMS").read_text())
                self.assertTrue(apk["signature"]["verified"])
                self.assertEqual(apk["signature"]["signerCertificateSha256"], ["ab" * 32])
        self.assertIn("required", record["deviceVerification"])
        self.assertTrue((self.destination / "reports/host-check.txt").exists())

    def test_failed_host_gate_removes_previous_delivery(self):
        self.destination.mkdir(parents=True)
        (self.destination / "stale.apk").write_bytes(b"old marker")
        self.environment["TEST_HOST_EXIT"] = "1"
        self.assert_failed_closed()

    def test_missing_second_apk_publishes_neither(self):
        (self.root / "app/build/outputs/apk/connected/debug/app-connected-debug.apk").unlink()
        self.assert_failed_closed()

    def test_missing_official_signer_rejects_pipeline(self):
        (self.root / "sdk/build-tools/35.0.0/apksigner").unlink()
        self.assert_failed_closed()

    def test_signature_failure_and_missing_certificate_reject_pipeline(self):
        for variable in ("TEST_BAD_SIGNATURE", "TEST_NO_CERTIFICATE"):
            with self.subTest(variable=variable):
                self.environment[variable] = "1"
                self.assert_failed_closed()
                del self.environment[variable]

    def test_zipalign_failure_rejects_pipeline(self):
        self.environment["TEST_ZIPALIGN_EXIT"] = "1"
        self.assert_failed_closed()

    def test_wrong_application_id_or_release_build_rejects_pipeline(self):
        for variable in ("TEST_WRONG_ID", "TEST_NOT_DEBUG"):
            with self.subTest(variable=variable):
                self.environment[variable] = "1"
                self.assert_failed_closed()
                del self.environment[variable]

    def test_source_zip_cannot_replace_apk(self):
        apk = self.root / "app/build/outputs/apk/offline/debug/app-offline-debug.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("README.md", b"synthetic source archive marker")
        self.assert_failed_closed()

    def test_missing_production_dependency_inventory_rejects_pipeline(self):
        (self.root / "app/build/reports/dependencies/production-runtime-components.txt").unlink()
        self.assert_failed_closed()


if __name__ == "__main__":
    unittest.main(verbosity=2)
