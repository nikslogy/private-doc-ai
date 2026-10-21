#!/usr/bin/env python3
"""Exercise validation gates with fake external-tool output, never a real device."""

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
MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35"/>
{permissions}
<application android:allowBackup="false" android:usesCleartextTraffic="false" android:dataExtractionRules="@xml/data_extraction_rules"/>
</manifest>'''


class ValidationGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copytree(ROOT / "scripts", self.root / "scripts", ignore=shutil.ignore_patterns("__pycache__"))
        (self.root / "config").mkdir()
        self.toolchain = json.loads((ROOT / "config/toolchain.json").read_text())
        self.wrapper_bytes = b"synthetic-wrapper-download-test-only"
        self.toolchain["gradleWrapper"]["sha256"] = hashlib.sha256(self.wrapper_bytes).hexdigest()
        self.write_toolchain()
        (self.root / "config/models.json").write_text(json.dumps({"schemaVersion": 1, "models": []}))
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.environment = os.environ.copy()
        self.environment.pop("ANDROID_SERIAL", None)
        # Tool mocks must not depend on host Java/proxy configuration.
        for name in ("JAVA_HOME", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
                     "http_proxy", "https_proxy", "all_proxy", "no_proxy"):
            self.environment.pop(name, None)
        self.environment["PATH"] = str(self.bin) + os.pathsep + self.environment["PATH"]
        self.environment["ANDROID_SDK_ROOT"] = str(self.root / "sdk")
        self.manifest = self.root / "manifest.xml"
        self.environment["TEST_MANIFEST_PATH"] = str(self.manifest)
        analyzer = self.bin / "apkanalyzer"
        analyzer.write_text(f"#!{sys.executable}\nimport os,pathlib\nprint(pathlib.Path(os.environ['TEST_MANIFEST_PATH']).read_text())\n")
        analyzer.chmod(0o755)
        self.apk = self.root / "test.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic marker; manifest tool output is mocked")

    def write_toolchain(self):
        (self.root / "config/toolchain.json").write_text(json.dumps(self.toolchain))

    def executable(self, path: Path, text: str = "#!/bin/sh\nexit 0\n"):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        path.chmod(0o755)

    def fake_bootstrap_tools(self):
        self.executable(self.bin / "java", '#!/bin/sh\necho "    java.specification.version = 17" >&2\n')
        self.executable(self.root / "gradlew")
        self.executable(self.root / ".tooling" / ("gradle-" + self.toolchain["gradle"]["version"]) / "bin/gradle")
        sdk = self.root / "sdk"
        tools = self.toolchain["commandLineTools"]["version"]
        self.executable(sdk / "cmdline-tools" / tools / "bin/sdkmanager")
        platform = sdk / "platforms" / ("android-" + str(self.toolchain["sdk"]["compile"])) / "android.jar"
        platform.parent.mkdir(parents=True)
        platform.write_bytes(b"synthetic android.jar marker")
        self.executable(sdk / "build-tools" / self.toolchain["sdk"]["buildTools"] / "aapt")
        self.executable(self.bin / "curl", f'''#!{sys.executable}
from pathlib import Path
import sys
arguments = sys.argv[1:]
assert arguments[-1] == {self.toolchain['gradleWrapper']['url']!r}, 'Unexpected download attempted'
Path(arguments[arguments.index('--output') + 1]).write_bytes({self.wrapper_bytes!r})
''')

    def run_script(self, script: str, *arguments: str):
        return subprocess.run(["bash", str(self.root / "scripts" / script), *arguments], env=self.environment,
                              text=True, capture_output=True, timeout=15)

    def test_offline_artifact_rejects_inherited_internet(self):
        self.manifest.write_text(MANIFEST.format(permissions='<uses-permission android:name="android.permission.INTERNET"/>'))
        result = self.run_script("verify-artifact.sh", "offline", str(self.apk))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unexpected INTERNET", result.stderr)

    def test_artifact_verifier_accepts_offline_and_connected_manifest_contracts(self):
        for flavor, permissions in (("offline", ""), ("connected", '<uses-permission android:name="android.permission.INTERNET"/>')):
            with self.subTest(flavor=flavor):
                self.manifest.write_text(MANIFEST.format(permissions=permissions))
                result = self.run_script("verify-artifact.sh", flavor, str(self.apk))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("SHA-256", result.stdout)

    def test_artifact_rejects_broad_storage_permission(self):
        self.manifest.write_text(MANIFEST.format(permissions='<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"/>'))
        result = self.run_script("verify-artifact.sh", "offline", str(self.apk))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Forbidden broad permission", result.stderr)

    def test_artifact_native_gate_rejects_invalid_zip_after_valid_mock_manifest(self):
        self.manifest.write_text(MANIFEST.format(permissions=""))
        self.apk.write_bytes(b"synthetic invalid ZIP marker")
        result = self.run_script("verify-artifact.sh", "offline", str(self.apk))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Native compatibility gate FAILED", result.stderr)
        self.assertNotIn("SHA-256", result.stdout)

    def test_no_device_or_model_is_not_a_passing_gate(self):
        device = self.run_script("check-device.sh")
        self.assertEqual(device.returncode, 2)
        self.assertIn("ANDROID_SERIAL", device.stderr)
        model = self.run_script("check-model.sh")
        self.assertEqual(model.returncode, 3)
        self.assertIn("No approved model", model.stderr)

    def test_launcher_alone_does_not_satisfy_bootstrap_check(self):
        self.executable(self.bin / "java", '#!/bin/sh\necho "    java.specification.version = 17" >&2\n')
        self.executable(self.root / "gradlew")
        result = self.run_script("bootstrap-android.sh", "--check-only")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("neither the verified wrapper JAR", result.stderr)
        self.assertIn("Missing SDK platform", result.stderr)
        self.assertIn("Missing SDK build-tools", result.stderr)

    def test_bootstrap_downloads_and_hashes_exact_official_wrapper_url(self):
        self.fake_bootstrap_tools()
        result = self.run_script("bootstrap-android.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "gradle/wrapper/gradle-wrapper.jar").read_bytes(), self.wrapper_bytes)
        self.assertIn("Verified SHA-256: gradle-wrapper.jar", result.stdout)

    def test_bootstrap_rejects_wrong_wrapper_hash(self):
        self.fake_bootstrap_tools()
        self.toolchain["gradleWrapper"]["sha256"] = "0" * 64
        self.write_toolchain()
        result = self.run_script("bootstrap-android.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SHA-256 mismatch", result.stderr)

    def test_bootstrap_rejects_wrapper_url_outside_exact_gradle_tag(self):
        self.fake_bootstrap_tools()
        self.toolchain["gradleWrapper"]["url"] = "https://raw.githubusercontent.com/untrusted/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
        self.write_toolchain()
        result = self.run_script("bootstrap-android.sh", "--check-only")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exact official Gradle GitHub tag", result.stderr)

    def test_check_only_accepts_installed_distribution_and_rejects_tampered_wrapper(self):
        self.fake_bootstrap_tools()
        accepted = self.run_script("bootstrap-android.sh", "--check-only")
        self.assertEqual(accepted.returncode, 0, accepted.stderr)
        wrapper = self.root / "gradle/wrapper/gradle-wrapper.jar"
        wrapper.parent.mkdir(parents=True)
        wrapper.write_bytes(b"tampered wrapper")
        rejected = self.run_script("bootstrap-android.sh", "--check-only")
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("wrapper JAR SHA-256 mismatch", rejected.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
