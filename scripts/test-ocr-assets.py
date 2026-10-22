#!/usr/bin/env python3
"""Synthetic tests of OCR pin validation, publication and failures; no network/models."""

import copy
import hashlib
import importlib.util
import json
import io
from unittest.mock import patch
from pathlib import Path
import tempfile
import unittest

MODULE_PATH = Path(__file__).with_name("prepare-ocr-assets.py")
SPEC = importlib.util.spec_from_file_location("ocr_assets", MODULE_PATH)
ASSETS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ASSETS)

DEPENDENCY_SPEC = importlib.util.spec_from_file_location("ocr_dependency", Path(__file__).with_name("verify-ocr-dependency.py"))
DEPENDENCY = importlib.util.module_from_spec(DEPENDENCY_SPEC)
DEPENDENCY_SPEC.loader.exec_module(DEPENDENCY)


class OcrAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.destination = self.root / "generated/assets"
        self.payloads = {language: f"synthetic-{language}-traineddata".encode() for language in ("eng", "hin", "mar")}
        self.revision = "a" * 40
        base = f"https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/{self.revision}/"
        self.catalogue = {
            "schemaVersion": 1, "id": "tessdata-fast-eng-hin-mar-v1",
            "engine": {"id": "tesseract4android", "version": "4.9.0", "nativeVersion": "5.5.1"},
            "repository": ASSETS.REPOSITORY, "revision": self.revision,
            "license": {"identifier": "Apache-2.0", "url": base + "LICENSE"},
            "models": [{"language": language, "fileName": language + ".traineddata", "byteCount": len(payload),
                        "sha256": hashlib.sha256(payload).hexdigest(), "url": base + language + ".traineddata"}
                       for language, payload in self.payloads.items()],
        }
        self.fetched = []

    def fetch(self, model, destination):
        self.fetched.append(model["language"])
        destination.write_bytes(self.payloads[model["language"]])

    def prepare(self):
        ASSETS.prepare_assets(self.catalogue, self.destination, download=True, fetch=self.fetch)

    def test_verified_publication_and_no_network_reverification(self):
        self.prepare()
        self.assertEqual(self.fetched, ["eng", "hin", "mar"])
        self.assertEqual(json.loads((self.destination / "ocr/catalogue.json").read_text()), self.catalogue)
        ASSETS.prepare_assets(self.catalogue, self.destination, download=False)
        self.prepare()
        self.assertEqual(self.fetched, ["eng", "hin", "mar"])
        self.assertEqual(list(self.destination.parent.glob(".ocr-assets-*")), [])

    def test_verify_only_fails_when_assets_are_absent(self):
        with self.assertRaises(ASSETS.AssetError):
            ASSETS.prepare_assets(self.catalogue, self.destination)
        self.assertFalse(self.destination.exists())

    def test_wrong_bytes_never_replace_existing_complete_directory(self):
        self.prepare()
        before = (self.destination / "ocr/catalogue.json").read_bytes()
        changed = copy.deepcopy(self.catalogue)
        changed["models"][0]["sha256"] = "0" * 64
        with self.assertRaises(ASSETS.AssetError):
            ASSETS.prepare_assets(changed, self.destination, download=True, fetch=self.fetch)
        self.assertEqual((self.destination / "ocr/catalogue.json").read_bytes(), before)
        ASSETS.verify_assets(self.catalogue, self.destination)
        self.assertFalse(self.destination.with_name("assets.previous").exists())
        self.assertEqual(list(self.destination.parent.glob(".ocr-assets-*")), [])

    def test_bad_size_and_corruption_fail_without_publishing(self):
        for payload in (b"", b"wrong-payload", b"x" * 100):
            with self.subTest(payload=payload):
                with self.assertRaises(ASSETS.AssetError):
                    ASSETS.prepare_assets(self.catalogue, self.destination, download=True,
                                          fetch=lambda model, target: target.write_bytes(payload))
                self.assertFalse(self.destination.exists())

    def test_one_invalid_cached_asset_is_repaired_only_after_verification(self):
        self.prepare()
        (self.destination / "ocr/tessdata/hin.traineddata").write_bytes(b"bad")
        with self.assertRaises(ASSETS.AssetError):
            ASSETS.verify_assets(self.catalogue, self.destination)
        self.fetched.clear()
        self.prepare()
        self.assertEqual(self.fetched, ["hin"])
        ASSETS.verify_assets(self.catalogue, self.destination)

    def test_unknown_url_revision_filename_language_licence_or_fields_rejected(self):
        mutations = (
            lambda c: c.update(revision="main"),
            lambda c: c.update(repository="https://example.org"),
            lambda c: c.update(schemaVersion=True),
            lambda c: c.update(extra="unreviewed"),
            lambda c: c["engine"].update(version="4.8.0"),
            lambda c: c["license"].update(url="https://example.org/LICENSE"),
            lambda c: c["models"][0].update(url="http://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata"),
            lambda c: c["models"][0].update(url="https://raw.githubusercontent.com.evil.example/model"),
            lambda c: c["models"][0].update(fileName="../../eng.traineddata"),
            lambda c: c["models"][0].update(language="../eng"),
            lambda c: c["models"][0].update(byteCount=True),
            lambda c: c["models"][0].update(byteCount=ASSETS.MAX_MODEL_BYTES + 1),
            lambda c: c["models"][0].update(sha256="placeholder"),
            lambda c: c["models"].append(copy.deepcopy(c["models"][0])),
            lambda c: c["models"][0].update(language="hin"),
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                invalid = copy.deepcopy(self.catalogue)
                mutation(invalid)
                with self.assertRaises(ASSETS.AssetError):
                    ASSETS.prepare_assets(invalid, self.destination, download=True, fetch=self.fetch)
        self.assertFalse(self.fetched)
        self.assertFalse(self.destination.exists())

    def test_symlink_output_ancestor_or_model_is_never_followed(self):
        outside = self.root / "outside"
        outside.mkdir()
        self.destination.parent.mkdir(parents=True)
        self.destination.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ASSETS.AssetError):
            self.prepare()
        self.assertFalse(list(outside.iterdir()))
        self.destination.unlink()
        self.prepare()
        model = self.destination / "ocr/tessdata/eng.traineddata"
        model.unlink()
        secret = outside / "canary"
        secret.write_bytes(b"synthetic-unrelated-canary")
        model.symlink_to(secret)
        with self.assertRaises(ASSETS.AssetError):
            ASSETS.verify_assets(self.catalogue, self.destination)
        self.assertEqual(secret.read_bytes(), b"synthetic-unrelated-canary")

    def test_missing_extra_or_changed_catalogue_assets_fail(self):
        self.prepare()
        extra = self.destination / "ocr/tessdata/unreviewed.traineddata"
        extra.write_bytes(b"synthetic-extra")
        with self.assertRaises(ASSETS.AssetError):
            ASSETS.verify_assets(self.catalogue, self.destination)
        extra.unlink()
        (self.destination / "ocr/catalogue.json").write_text("{}")
        with self.assertRaises(ASSETS.AssetError):
            ASSETS.verify_assets(self.catalogue, self.destination)

    def test_interrupted_download_preserves_prior_outputs(self):
        self.prepare()
        (self.destination / "ocr/tessdata/mar.traineddata").write_bytes(b"old-invalid")
        def interrupted(model, target):
            target.write_bytes(b"partial")
            raise OSError("synthetic interrupted download")
        with self.assertRaises(OSError):
            ASSETS.prepare_assets(self.catalogue, self.destination, download=True, fetch=interrupted)
        self.assertEqual((self.destination / "ocr/tessdata/mar.traineddata").read_bytes(), b"old-invalid")
        self.assertEqual(list(self.destination.parent.glob(".ocr-assets-*")), [])

    def test_downloader_rejects_redirect_and_wrong_stream_bytes(self):
        model = self.catalogue["models"][0]
        class Response(io.BytesIO):
            status = 200
            def geturl(self):
                return model["url"]
        class Opener:
            def __init__(self, payload):
                self.payload = payload
            def open(self, url, timeout):
                self.request = (url, timeout)
                return Response(self.payload)
        for payload, accepted in ((self.payloads["eng"], True), (b"bad", False),
                                  (self.payloads["eng"] + b"extra", False)):
            target = self.root / "download.part"
            target.unlink(missing_ok=True)
            opener = Opener(payload)
            with self.subTest(payload=payload), patch.object(ASSETS, "build_opener", return_value=opener):
                if accepted:
                    ASSETS.download_model(model, target)
                    self.assertEqual(target.read_bytes(), payload)
                else:
                    with self.assertRaises(ASSETS.AssetError):
                        ASSETS.download_model(model, target)
            self.assertEqual(opener.request, (model["url"], 45))
        with self.assertRaises(ASSETS.AssetError):
            ASSETS.NoRedirects().redirect_request(None, None, 302, "synthetic", {}, "https://example.org")

    def test_regular_file_output_is_preserved_and_rejected(self):
        self.destination.parent.mkdir(parents=True)
        self.destination.write_bytes(b"synthetic-existing-output")
        with self.assertRaises(ASSETS.AssetError):
            self.prepare()
        self.assertEqual(self.destination.read_bytes(), b"synthetic-existing-output")

    def test_stale_publication_backup_is_not_deleted(self):
        self.prepare()
        previous = self.destination.with_name("assets.previous")
        previous.mkdir()
        marker = previous / "synthetic-marker"
        marker.write_bytes(b"keep")
        (self.destination / "ocr/tessdata/mar.traineddata").write_bytes(b"bad")
        with self.assertRaises(ASSETS.AssetError):
            self.prepare()
        self.assertEqual(marker.read_bytes(), b"keep")


class OcrDependencyPinTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "synthetic.aar"
        payload = b"synthetic-arbitrary-library-bytes"
        self.path.write_bytes(payload)
        self.record = {"schemaVersion": 1, "coordinate": DEPENDENCY.COORDINATE,
                       "sourceRevision": "15c534717b1cb58261b58d4e4c1200c7f81f668c",
                       "artifact": {"url": DEPENDENCY.URL, "byteCount": len(payload),
                                    "sha256": hashlib.sha256(payload).hexdigest()}}

    def test_exact_pin_accepts_bytes_and_detects_same_size_tampering(self):
        DEPENDENCY.verify_artifact(self.record, self.path)
        data = self.path.read_bytes()
        self.path.write_bytes(b"X" + data[1:])
        with self.assertRaises(ValueError):
            DEPENDENCY.verify_artifact(self.record, self.path)

    def test_missing_truncated_oversized_or_invalid_pin_is_rejected(self):
        mutations = (
            lambda r: r.update(schemaVersion=True),
            lambda r: r.update(coordinate="unreviewed:module:4.9.0"),
            lambda r: r.update(sourceRevision="main"),
            lambda r: r["artifact"].update(url="https://example.org/library.aar"),
            lambda r: r["artifact"].update(byteCount=True),
            lambda r: r["artifact"].update(byteCount=64 * 1024**2 + 1),
            lambda r: r["artifact"].update(byteCount=1),
            lambda r: r["artifact"].update(sha256="unverified"),
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                invalid = copy.deepcopy(self.record)
                mutation(invalid)
                with self.assertRaises(ValueError):
                    DEPENDENCY.verify_artifact(invalid, self.path)
        self.path.unlink()
        with self.assertRaises(ValueError):
            DEPENDENCY.verify_artifact(self.record, self.path)


if __name__ == "__main__":
    unittest.main()
