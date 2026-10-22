#!/usr/bin/env python3
"""Synthetic stable-identity delivery checks; no private signing key or real APK."""

import importlib.util
import base64
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import shutil
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


class StableSigningTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "config").mkdir()
        self.record = {"schemaVersion": 1, "alias": "private-assistant-dev", "certificateSha256": "a" * 64,
                       "applicationIds": {"offline": "com.privateassistant.app.preview",
                                          "connected": "com.privateassistant.app.connected.preview"}}
        self.write_record()
        spec = importlib.util.spec_from_file_location("staging_test", ROOT / "scripts/stage-apks.py")
        self.staging = importlib.util.module_from_spec(spec)
        with patch.dict(os.environ, {"ORG_GRADLE_PROJECT_stableDevelopment": "true"}):
            spec.loader.exec_module(self.staging)
        self.staging.ROOT = self.root

    def write_record(self):
        (self.root / "config/development-signing.json").write_text(json.dumps(self.record))

    def test_stable_identity_requires_exact_modern_signer(self):
        text = "Verified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: " + "a" * 64 + "\n"
        self.assertTrue(self.staging.signing_metadata(text, "offline")["verified"])
        for changed in (text.replace("a" * 64, "b" * 64), text.replace(": true", ": false"),
                        text + "Signer #2 certificate SHA-256 digest: " + "b" * 64 + "\n"):
            with self.subTest(changed=changed):
                with self.assertRaises(self.staging.StagingError):
                    self.staging.signing_metadata(changed, "offline")

    def test_stable_mode_cannot_publish_old_or_unexpected_application_ids(self):
        def badging(application_id):
            return f"package: name='{application_id}' versionCode='3' versionName='0.2.0-dev'\nsdkVersion:'31'\ntargetSdkVersion:'35'\napplication-debuggable\n"
        self.staging.metadata(badging("com.privateassistant.app.preview"), "offline", 31, 35)
        for application_id in ("com.privateassistant.app.dev", "com.privateassistant.app", "com.privateassistant.app.connected.preview"):
            with self.assertRaises(self.staging.StagingError):
                self.staging.metadata(badging(application_id), "offline", 31, 35)

    def test_partial_or_unknown_public_identity_is_rejected(self):
        for field, value in (("alias", "unknown"), ("certificateSha256", ""), ("certificateSha256", None),
                             ("schemaVersion", True), ("applicationIds", {})):
            old = self.record[field]
            self.record[field] = value
            self.write_record()
            with self.assertRaises(self.staging.StagingError):
                self.staging.stable_signing_record()
            self.record[field] = old

    def test_missing_secret_stops_before_host_or_artifact_creation(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(self.staging.StagingError):
                self.staging.stage()
        self.assertFalse((self.root / "artifacts").exists())

    def preparation(self, destination):
        (self.root / "scripts").mkdir(exist_ok=True)
        shutil.copyfile(ROOT / "scripts/stage-apks.py", self.root / "scripts/stage-apks.py")
        spec = importlib.util.spec_from_file_location("preparation_test", ROOT / "scripts/prepare-development-signing.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.ROOT = self.root
        environment = {"GITHUB_ACTIONS": "true", "GITHUB_REF": "refs/heads/main", "RUNNER_TEMP": str(self.root),
                       "ANDROID_DEV_KEYSTORE_PATH": str(destination),
                       "ANDROID_DEV_KEYSTORE_BASE64": base64.b64encode(b"synthetic-key-fixture" * 100).decode(),
                       "ANDROID_DEV_STORE_PASSWORD": "SYNTHETIC_SECRET_NOT_FOR_LOGS"}
        return module, environment

    def test_preparation_never_removes_a_preexisting_key_or_symlink(self):
        parent = self.root / "private-assistant-signing.synthetic"
        parent.mkdir()
        destination = parent / "development.p12"
        destination.write_bytes(b"synthetic-existing-key-sentinel")
        module, environment = self.preparation(destination)
        with patch.dict(os.environ, environment, clear=True), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(module.main(), 1)
        self.assertEqual(destination.read_bytes(), b"synthetic-existing-key-sentinel")
        destination.unlink()
        target = self.root / "preserved-sentinel"
        target.write_bytes(b"synthetic-target")
        destination.symlink_to(target)
        with patch.dict(os.environ, environment, clear=True), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(module.main(), 1)
        self.assertTrue(destination.is_symlink())
        self.assertEqual(target.read_bytes(), b"synthetic-target")

    def test_wrong_certificate_removes_only_new_copy_without_printing_credentials(self):
        parent = self.root / "private-assistant-signing.synthetic"
        parent.mkdir()
        destination = parent / "development.p12"
        module, environment = self.preparation(destination)
        output = io.StringIO()
        # Synthetic subprocess result; actual keytool identity is separately checked with the real key.
        with patch.dict(os.environ, environment, clear=True), contextlib.redirect_stderr(output), \
                patch.object(module.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=b"synthetic-other-certificate")):
            self.assertEqual(module.main(), 1)
        self.assertFalse(destination.exists())
        self.assertNotIn(environment["ANDROID_DEV_STORE_PASSWORD"], output.getvalue())
        self.assertNotIn(environment["ANDROID_DEV_KEYSTORE_BASE64"], output.getvalue())


if __name__ == "__main__":
    unittest.main()
