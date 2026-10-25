#!/usr/bin/env python3
"""Synthetic transport and reviewed-public-metadata admission tests; no weights downloaded."""
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("public_model_probe", Path(__file__).with_name("download-probe-model.py"))
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)


def reviewed_catalogue(model_id="qwen3-06b-q4-nothink"):
    repository, revision, filename, count, digest, runtime, terms = PROBE.REVIEWED_MODELS[model_id]
    return {"schemaVersion": 1, "runtime": {"id": "litert-lm", "version": runtime, "format": "litertlm"},
            "models": [{"id": model_id, "assessment": "experimental", "revision": revision,
                        "fileName": filename, "byteCount": count, "sha256": digest,
                        "runtimeVersion": runtime, "format": "litertlm", "backends": ["CPU"],
                        "evaluatedLanguages": [], "evaluatedTasks": [],
                        "license": {"identifier": "Apache-2.0", "termsUrl": terms, "requiresAcceptance": True},
                        "downloadUrl": f"https://huggingface.co/{repository}/resolve/{revision}/{filename}?download=true"}]}


class Response(io.BytesIO):
    def __init__(self, payload, content_length=None):
        super().__init__(payload)
        self.headers = {} if content_length is None else {"Content-Length": str(content_length)}


class ReviewedMetadataTest(unittest.TestCase):
    def reject(self, catalogue, model_id="qwen3-06b-q4-nothink"):
        with self.assertRaises(ValueError):
            PROBE.validate_reviewed_model(catalogue, model_id)

    def test_each_exact_public_identity_requires_its_own_runtime(self):
        for model_id in PROBE.REVIEWED_MODELS:
            with self.subTest(model_id=model_id):
                catalogue = reviewed_catalogue(model_id)
                self.assertIs(PROBE.validate_reviewed_model(catalogue, model_id), catalogue["models"][0])

    def test_unknown_missing_and_duplicate_selections_are_rejected(self):
        self.reject(reviewed_catalogue(), "unknown-model")
        catalogue = reviewed_catalogue()
        catalogue["models"].clear()
        self.reject(catalogue)
        catalogue = reviewed_catalogue()
        catalogue["models"].append(copy.deepcopy(catalogue["models"][0]))
        self.reject(catalogue)

    def test_moving_revisions_paths_sizes_and_hash_substitutions_are_rejected(self):
        for field, values in {
            "revision": ("main", "a" * 40),
            "fileName": ("../model.litertlm", "same-name-different-export.litertlm"),
            "byteCount": (0, -1, 347_251_839, 347_251_841, 5 * 1024**3),
            "sha256": ("0" * 64, "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139".upper()),
        }.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    catalogue = reviewed_catalogue()
                    catalogue["models"][0][field] = value
                    self.reject(catalogue)

    def test_url_cannot_redirect_the_initial_request_to_another_identity(self):
        exact = reviewed_catalogue()["models"][0]["downloadUrl"]
        for url in (
            exact.replace("https://", "http://"), exact.replace("huggingface.co", "huggingface.co.evil.invalid"),
            exact.replace("huggingface.co/", "huggingface.co:8443/"),
            exact.replace("https://", "https://user:secret@"), exact + "#fragment",
            exact.replace("/resolve/256f33043931947c173d7f3cc1d84ee7a8538cc2/", "/resolve/main/"),
            exact.replace("Qwen3-0.6B-int4/", "another-model/"), exact.replace("?download=true", "?download=true&token=secret"),
        ):
            with self.subTest(url=url):
                catalogue = reviewed_catalogue()
                catalogue["models"][0]["downloadUrl"] = url
                self.reject(catalogue)

    def test_catalogue_and_artifact_runtime_must_both_match_the_review(self):
        for location, field, value in (
            ("runtime", "version", "0.9.0"), ("runtime", "id", "remote-api"),
            ("runtime", "format", "gguf"), ("model", "runtimeVersion", "0.18.1"),
            ("model", "format", "tflite"),
        ):
            catalogue = reviewed_catalogue()
            target = catalogue["runtime"] if location == "runtime" else catalogue["models"][0]
            target[field] = value
            self.reject(catalogue)
        catalogue = reviewed_catalogue("qwen25-15b-q8-legacy")
        catalogue["runtime"]["version"] = "0.16.0"
        self.reject(catalogue, "qwen25-15b-q8-legacy")

    def test_licence_identity_terms_and_acknowledgement_cannot_be_changed(self):
        for field, value in (("identifier", "unreviewed"), ("termsUrl", "https://example.invalid/licence"),
                             ("requiresAcceptance", False)):
            catalogue = reviewed_catalogue()
            catalogue["models"][0]["license"][field] = value
            self.reject(catalogue)

    def test_experimental_unmeasured_cpu_scope_is_required(self):
        for field, value in (("assessment", "validated"), ("backends", ["REMOTE"]),
                             ("evaluatedTasks", ["document-qa"]), ("evaluatedLanguages", ["en"])):
            catalogue = reviewed_catalogue()
            catalogue["models"][0][field] = value
            self.reject(catalogue)


class SyntheticDownloadTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        (self.root / "config").mkdir()
        (self.root / "config/models.json").write_text("{}")
        self.output = self.root / "synthetic.litertlm"
        self.report = self.root / "report.json"
        self.payload = b"synthetic-public-model-bytes-for-downloader-tests-only"
        self.model = {"id": "synthetic-model", "revision": "a" * 40, "runtimeVersion": "synthetic",
                      "byteCount": len(self.payload), "sha256": hashlib.sha256(self.payload).hexdigest(),
                      "downloadUrl": "https://example.invalid/synthetic-model", "license": {"identifier": "synthetic"}}
        self.arguments = ["download-probe-model.py", "--model-id", "synthetic-model", "--output", str(self.output),
                          "--report", str(self.report), "--download", "--accept-license"]

    def run_download(self, response=None, env=None, monotonic=None):
        with patch.object(PROBE, "ROOT", self.root), patch.object(PROBE.sys, "argv", self.arguments), \
                patch.object(PROBE, "validate_reviewed_model", return_value=self.model), \
                patch.object(PROBE, "urlopen", return_value=response or Response(self.payload)) as opened, \
                patch.dict(os.environ, env or {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted"}), \
                patch.object(PROBE.time, "monotonic", side_effect=monotonic or [0, 1, 2]):
            PROBE.main()
            return opened

    def assert_clean_failure(self):
        self.assertFalse(self.output.exists())
        self.assertFalse(self.output.with_name(self.output.name + ".part").exists())
        self.assertFalse(self.report.exists())

    def test_explicit_disposable_ci_runner_is_required_before_network(self):
        for env in ({"GITHUB_ACTIONS": "false", "RUNNER_ENVIRONMENT": "github-hosted"},
                    {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "self-hosted"}):
            with self.assertRaises(ValueError):
                self.run_download(env=env)
            self.assert_clean_failure()

    def test_verified_synthetic_stream_publishes_hash_report_and_retains_tls_defaults(self):
        opened = self.run_download(Response(self.payload, len(self.payload)))
        self.assertEqual(self.output.read_bytes(), self.payload)
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o600)
        self.assertFalse(self.output.with_name(self.output.name + ".part").exists())
        report = json.loads(self.report.read_text())
        self.assertEqual(report["byteCount"], len(self.payload))
        self.assertEqual(report["sha256"], hashlib.sha256(self.payload).hexdigest())
        self.assertEqual(opened.call_args.args[0].full_url, self.model["downloadUrl"])
        self.assertEqual(opened.call_args.kwargs, {"timeout": 60})

    def test_same_size_corruption_truncation_oversize_and_wrong_length_remove_partial(self):
        for payload, header in ((b"X" + self.payload[1:], None), (self.payload[:-1], None),
                                (self.payload + b"X", None), (self.payload, len(self.payload) + 1)):
            with self.subTest(bytes=len(payload), header=header), self.assertRaises(ValueError):
                self.run_download(Response(payload, header))
            self.assert_clean_failure()

    def test_download_deadline_removes_partial_without_publishing(self):
        with self.assertRaises(ValueError):
            self.run_download(monotonic=[0, 1201])
        self.assert_clean_failure()

    def test_existing_or_symlink_destination_is_preserved(self):
        self.output.write_bytes(b"existing-synthetic-data")
        with self.assertRaises(ValueError):
            self.run_download()
        self.assertEqual(self.output.read_bytes(), b"existing-synthetic-data")
        self.output.unlink()
        target = self.root / "preserve.txt"
        target.write_bytes(b"synthetic-symlink-target")
        self.output.symlink_to(target)
        with self.assertRaises(ValueError):
            self.run_download()
        self.assertEqual(target.read_bytes(), b"synthetic-symlink-target")
        self.assertFalse(self.report.exists())


if __name__ == "__main__":
    unittest.main()
