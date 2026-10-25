#!/usr/bin/env python3
"""Synthetic cache/publication/transport tests for the pinned OCR AAR bootstrap."""

import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import socket
import ssl
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.request import HTTPSHandler, ProxyHandler

SPEC = importlib.util.spec_from_file_location("ocr_runtime_preparation", Path(__file__).with_name("prepare-ocr-runtime.py"))
RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNTIME)


class Response(io.BytesIO):
    def __init__(self, payload, url=RUNTIME.VERIFIER.URL, status=200):
        super().__init__(payload)
        self.url = url
        self.status = status

    def geturl(self):
        return self.url


class Opener:
    def __init__(self, actions):
        self.actions = list(actions)
        self.requests = []
        self.request_headers = []

    def open(self, request, timeout):
        self.requests.append((request.full_url, request.get_method(), timeout))
        self.request_headers.append({key.lower(): value for key, value in request.header_items()})
        action = self.actions.pop(0)
        if isinstance(action, BaseException):
            raise action
        return action if isinstance(action, Response) else Response(action)


class OcrRuntimePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "ignored/verified-ocr-maven"
        self.gradle_home = self.root / "ignored/gradle-user-home"
        self.target = self.repository / RUNTIME.ARTIFACT_PATH
        self.payload = b"synthetic-public-aar-fixture-481596"
        self.record = {
            "schemaVersion": 1, "coordinate": RUNTIME.VERIFIER.COORDINATE,
            "sourceRevision": "15c534717b1cb58261b58d4e4c1200c7f81f668c",
            "artifact": {"url": RUNTIME.VERIFIER.URL, "byteCount": len(self.payload),
                         "sha256": hashlib.sha256(self.payload).hexdigest()},
        }
        self.fetches = []

    def fetch(self, record, destination):
        self.fetches.append(destination)
        destination.write_bytes(self.payload)

    def prepare(self, fetch=None):
        return RUNTIME.prepare_runtime(self.record, self.repository, download=True, fetch=fetch or self.fetch,
                                       gradle_user_home=self.gradle_home)

    def gradle_entry(self, home=None, version="4.9.0", group="com.github.adaptech-cz.Tesseract4Android"):
        return (home or self.gradle_home) / "caches/modules-2/files-2.1" / group / "tesseract4android" / version / ("a" * 40) / RUNTIME.ARTIFACT_PATH.name

    def assert_no_staging(self):
        self.assertEqual(list(self.repository.rglob(".ocr-runtime-*")) if self.repository.exists() else [], [])

    def test_exact_maven_path_verified_publication_and_no_network_cache_reuse(self):
        self.assertEqual(self.prepare(), self.target)
        self.assertEqual(self.target.read_bytes(), self.payload)
        self.assertEqual(self.target.stat().st_mode & 0o777, 0o444)
        RUNTIME.prepare_runtime(self.record, self.repository, download=False)
        self.prepare()
        self.assertEqual(len(self.fetches), 1)
        self.assertEqual(self.target.relative_to(self.repository).as_posix(),
                         "com/github/adaptech-cz/Tesseract4Android/tesseract4android/4.9.0/tesseract4android-4.9.0.aar")
        self.assert_no_staging()

    def test_verify_only_missing_or_invalid_cache_never_downloads_or_creates_directories(self):
        with self.assertRaises(RUNTIME.PreparationError):
            RUNTIME.prepare_runtime(self.record, self.repository, download=False, fetch=self.fetch)
        self.assertFalse(self.repository.exists())
        self.target.parent.mkdir(parents=True)
        self.target.write_bytes(b"corrupted-cache")
        with self.assertRaises(RUNTIME.PreparationError):
            RUNTIME.prepare_runtime(self.record, self.repository, download=False, fetch=self.fetch)
        self.assertEqual(self.target.read_bytes(), b"corrupted-cache")
        self.assertEqual(self.fetches, [])

    def test_same_size_corruption_truncation_and_oversize_cannot_publish(self):
        wrong_same_size = b"X" + self.payload[1:]
        for payload in (wrong_same_size, self.payload[:-1], self.payload + b"X"):
            with self.subTest(payload=payload):
                with self.assertRaises(ValueError):
                    self.prepare(lambda record, destination: destination.write_bytes(payload))
                self.assertFalse(self.target.exists())
                self.assert_no_staging()

    def test_invalid_cache_replaced_only_with_verified_bytes(self):
        self.target.parent.mkdir(parents=True)
        self.target.write_bytes(b"old-invalid-cache")
        original_replace = RUNTIME.os.replace
        observations = []
        def checked_replace(source, destination):
            observations.append((Path(source).read_bytes(), Path(destination).read_bytes()))
            RUNTIME.VERIFIER.verify_artifact(self.record, source)
            return original_replace(source, destination)
        with patch.object(RUNTIME.os, "replace", side_effect=checked_replace):
            self.prepare()
        self.assertEqual(observations, [(self.payload, b"old-invalid-cache")])
        self.assertEqual(self.target.read_bytes(), self.payload)
        self.assert_no_staging()

    def test_interruption_or_publication_failure_preserves_old_cache_and_cleans_staging(self):
        self.target.parent.mkdir(parents=True)
        self.target.write_bytes(b"old-invalid-cache")
        def interrupted(record, destination):
            destination.write_bytes(b"partial-download")
            raise InterruptedError("synthetic interruption")
        with self.assertRaises(InterruptedError):
            self.prepare(interrupted)
        self.assertEqual(self.target.read_bytes(), b"old-invalid-cache")
        self.assert_no_staging()
        with patch.object(RUNTIME.os, "replace", side_effect=OSError("synthetic publication failure")):
            with self.assertRaises(OSError):
                self.prepare()
        self.assertEqual(self.target.read_bytes(), b"old-invalid-cache")
        self.assert_no_staging()

    def test_invalid_identity_or_source_rejected_before_fetch_or_filesystem_changes(self):
        mutations = (
            lambda p: p.update(schemaVersion=True),
            lambda p: p.update(coordinate="unreviewed:module:4.9.0"),
            lambda p: p.update(sourceRevision="main"),
            lambda p: p.update(extra="unknown"),
            lambda p: p["artifact"].update(url="http://jitpack.io/unreviewed.aar"),
            lambda p: p["artifact"].update(url="https://jitpack.io.evil.example/unreviewed.aar"),
            lambda p: p["artifact"].update(byteCount=True),
            lambda p: p["artifact"].update(byteCount=0),
            lambda p: p["artifact"].update(byteCount=64 * 1024**2 + 1),
            lambda p: p["artifact"].update(sha256="unverified"),
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(self.record)
                mutation(changed)
                with self.assertRaises(RUNTIME.PreparationError):
                    RUNTIME.prepare_runtime(changed, self.repository, download=True, fetch=self.fetch)
        self.assertEqual(self.fetches, [])
        self.assertFalse(self.repository.exists())

    def test_symlink_repository_ancestor_is_not_followed(self):
        outside = self.root / "outside"
        outside.mkdir()
        self.repository.parent.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(RUNTIME.PreparationError):
            self.prepare()
        self.assertEqual(list(outside.iterdir()), [])
        self.assertEqual(self.fetches, [])

    def test_symlink_cached_file_is_not_read_or_overwritten(self):
        self.target.parent.mkdir(parents=True)
        outside = self.root / "unrelated-canary"
        outside.write_bytes(self.payload)
        self.target.symlink_to(outside)
        with self.assertRaises(RUNTIME.PreparationError):
            self.prepare()
        self.assertEqual(outside.read_bytes(), self.payload)
        self.assertTrue(self.target.is_symlink())
        self.assertEqual(self.fetches, [])

    def test_symlink_staging_file_is_rejected_without_touching_outside(self):
        outside = self.root / "unrelated-canary"
        outside.write_bytes(self.payload)
        def linked(record, destination):
            destination.symlink_to(outside)
        with self.assertRaises(RUNTIME.PreparationError):
            self.prepare(linked)
        self.assertEqual(outside.read_bytes(), self.payload)
        self.assertFalse(self.target.exists())
        self.assert_no_staging()

    def test_directory_cache_entry_is_preserved(self):
        self.target.mkdir(parents=True)
        marker = self.target / "unrelated"
        marker.write_bytes(b"preserve")
        with self.assertRaises(RUNTIME.PreparationError):
            self.prepare()
        self.assertEqual(marker.read_bytes(), b"preserve")
        self.assertEqual(self.fetches, [])

    def download(self, actions):
        destination = self.root / "download.part"
        opener = Opener(actions)
        delays = []
        with patch.object(RUNTIME, "build_opener", return_value=opener) as build:
            RUNTIME.download_artifact(self.record, destination, pause=delays.append)
        return destination, opener, delays, build

    def test_downloader_uses_get_exact_origin_timeout_and_system_tls_proxy_handlers(self):
        destination, opener, delays, build = self.download([self.payload])
        self.assertEqual(destination.read_bytes(), self.payload)
        self.assertEqual(opener.requests, [(RUNTIME.VERIFIER.URL, "GET", 30)])
        self.assertEqual(delays, [])
        handlers = build.call_args.args
        self.assertTrue(any(isinstance(handler, ProxyHandler) for handler in handlers))
        self.assertTrue(any(isinstance(handler, RUNTIME.NoRedirects) for handler in handlers))
        tls = next(handler for handler in handlers if isinstance(handler, HTTPSHandler))._context
        self.assertEqual(tls.verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(tls.check_hostname)

    def test_request_headers_identify_project_and_disable_stale_or_encoded_responses(self):
        _, opener, _, _ = self.download([self.payload])
        self.assertEqual(opener.requests[0][0], RUNTIME.VERIFIER.URL)
        self.assertEqual(opener.request_headers, [{
            "user-agent": "private-doc-ai-verified-ocr-bootstrap/1",
            "accept-encoding": "identity", "cache-control": "no-cache",
        }])

    def test_exhausted_http_failure_exposes_safe_status_without_provider_content(self):
        marker = "SYNTHETIC_SECRET_PROVIDER_CONTENT_481596"
        error = HTTPError("https://user:password@example.invalid/" + marker, 503, marker, {}, None)
        opener = Opener([error, error, error])
        with patch.object(RUNTIME, "build_opener", return_value=opener):
            with self.assertRaises(RUNTIME.PreparationError) as captured:
                RUNTIME.download_artifact(self.record, self.root / "download.part", pause=lambda _: None)
        self.assertEqual(str(captured.exception), "Pinned OCR runtime download unavailable (HTTP_503); no artifact published")
        self.assertNotIn(marker, str(captured.exception))
        self.assertNotIn("password", str(captured.exception))
        self.assertFalse((self.root / "download.part").exists())

    def test_transport_diagnostics_use_known_class_without_exception_or_credentials(self):
        marker = "SYNTHETIC_PROXY_PASSWORD_481596"
        error = URLError(ssl.SSLCertVerificationError(1, marker))
        with patch.object(RUNTIME, "build_opener", return_value=Opener([error])):
            with self.assertRaises(RUNTIME.PreparationError) as captured:
                RUNTIME.download_artifact(self.record, self.root / "download.part", pause=lambda _: self.fail("TLS must not retry"))
        self.assertEqual(str(captured.exception), "Pinned OCR runtime download unavailable (SSLCertVerificationError); no artifact published")
        self.assertNotIn(marker, str(captured.exception))

    def test_exact_verified_gradle_artifact_is_copied_without_network(self):
        source = self.gradle_entry()
        source.parent.mkdir(parents=True)
        source.write_bytes(self.payload)
        self.assertEqual(self.prepare(), self.target)
        self.assertEqual(self.fetches, [])
        self.assertEqual(source.read_bytes(), self.payload)
        self.assertEqual(self.target.read_bytes(), self.payload)
        self.assertEqual(self.target.stat().st_mode & 0o777, 0o444)
        self.assert_no_staging()

    def test_corrupt_gradle_cache_is_preserved_and_only_verified_get_can_publish(self):
        source = self.gradle_entry()
        source.parent.mkdir(parents=True)
        corrupt = b"X" + self.payload[1:]
        source.write_bytes(corrupt)
        self.prepare()
        self.assertEqual(len(self.fetches), 1)
        self.assertEqual(source.read_bytes(), corrupt)
        self.assertEqual(self.target.read_bytes(), self.payload)
        self.assert_no_staging()

    def test_symlink_gradle_file_and_hash_directory_are_never_accepted(self):
        outside = self.root / "outside-gradle"
        outside.mkdir()
        canary = outside / RUNTIME.ARTIFACT_PATH.name
        canary.write_bytes(self.payload)
        for linked_directory in (False, True):
            with self.subTest(linked_directory=linked_directory):
                home = self.root / f"gradle-link-{linked_directory}"
                source = self.gradle_entry(home=home)
                source.parent.parent.mkdir(parents=True)
                if linked_directory:
                    source.parent.symlink_to(outside, target_is_directory=True)
                else:
                    source.parent.mkdir()
                    source.symlink_to(canary)
                repository = self.root / f"repository-link-{linked_directory}"
                before = len(self.fetches)
                RUNTIME.prepare_runtime(self.record, repository, download=True, fetch=self.fetch, gradle_user_home=home)
                self.assertEqual(len(self.fetches), before + 1)
                self.assertEqual(canary.read_bytes(), self.payload)

    def test_gradle_import_stays_within_exact_coordinate_and_canonical_home(self):
        for source in (self.gradle_entry(version="4.9.1"), self.gradle_entry(group="unrelated.group")):
            source.parent.mkdir(parents=True)
            source.write_bytes(self.payload)
        self.prepare()
        self.assertEqual(len(self.fetches), 1)
        # An explicitly aliased home cannot turn an outside cache into an import.
        outside_home = self.root / "outside-home"
        source = self.gradle_entry(home=outside_home)
        source.parent.mkdir(parents=True)
        source.write_bytes(self.payload)
        alias_parent = self.root / "alias-parent"
        alias_parent.mkdir()
        RUNTIME.prepare_runtime(self.record, self.root / "canonical-repository", download=True,
                                fetch=self.fetch, gradle_user_home=alias_parent / ".." / "outside-home")
        self.assertEqual(len(self.fetches), 2)

    def test_transient_http_errors_retry_bounded_then_accept_only_verified_bytes(self):
        for code in (404, 429, 500, 502, 503):
            with self.subTest(code=code):
                destination = self.root / "download.part"
                destination.unlink(missing_ok=True)
                error = HTTPError(RUNTIME.VERIFIER.URL, code, "synthetic temporary failure", {}, None)
                path, opener, delays, _ = self.download([error, error, self.payload])
                self.assertEqual(path.read_bytes(), self.payload)
                self.assertEqual(len(opener.requests), 3)
                self.assertEqual(delays, [1, 2])

    def test_exhausted_retries_never_create_published_or_partial_bytes(self):
        error = HTTPError(RUNTIME.VERIFIER.URL, 404, "synthetic unavailable", {}, None)
        opener = Opener([error, error, error])
        destination = self.root / "download.part"
        delays = []
        with patch.object(RUNTIME, "build_opener", return_value=opener):
            with self.assertRaises(RUNTIME.PreparationError):
                RUNTIME.download_artifact(self.record, destination, pause=delays.append)
        self.assertEqual(len(opener.requests), 3)
        self.assertEqual(delays, [1, 2])
        self.assertFalse(destination.exists())

    def test_nontransient_http_or_certificate_failures_do_not_retry_or_echo_credentials(self):
        marker = "SYNTHETIC_PROXY_PASSWORD_381549"
        failures = [HTTPError(RUNTIME.VERIFIER.URL, code, marker, {}, None) for code in (401, 403, 410)]
        failures += [URLError(ssl.SSLCertVerificationError(1, marker)), URLError(marker)]
        for failure in failures:
            with self.subTest(failure=type(failure).__name__):
                opener = Opener([failure])
                delays = []
                with patch.object(RUNTIME, "build_opener", return_value=opener):
                    with self.assertRaises(RUNTIME.PreparationError) as captured:
                        RUNTIME.download_artifact(self.record, self.root / "download.part", pause=delays.append)
                self.assertEqual(len(opener.requests), 1)
                self.assertEqual(delays, [])
                self.assertNotIn(marker, str(captured.exception))
                self.assertFalse((self.root / "download.part").exists())

    def test_transient_network_timeout_and_dns_retry_then_success(self):
        failures = (URLError(TimeoutError("synthetic timeout")),
                    URLError(socket.gaierror(socket.EAI_AGAIN, "synthetic temporary DNS")),
                    ConnectionResetError("synthetic reset"))
        for failure in failures:
            with self.subTest(failure=type(failure).__name__):
                (self.root / "download.part").unlink(missing_ok=True)
                destination, opener, delays, _ = self.download([failure, self.payload])
                self.assertEqual(destination.read_bytes(), self.payload)
                self.assertEqual(len(opener.requests), 2)
                self.assertEqual(delays, [1])

    def test_transport_interruption_discards_partial_stream_before_retry(self):
        class InterruptedResponse(Response):
            reads = 0
            def read(self, count):
                self.reads += 1
                if self.reads == 1:
                    return self.getvalue()[:5]
                raise ConnectionResetError("synthetic interrupted stream")
        destination, opener, delays, _ = self.download([InterruptedResponse(self.payload), self.payload])
        self.assertEqual(destination.read_bytes(), self.payload)
        self.assertEqual(len(opener.requests), 2)
        self.assertEqual(delays, [1])

    def test_corruption_truncation_and_oversize_fail_without_retry(self):
        for payload in (b"X" + self.payload[1:], self.payload[:-1], self.payload + b"X"):
            with self.subTest(payload=payload):
                opener = Opener([payload])
                with patch.object(RUNTIME, "build_opener", return_value=opener):
                    with self.assertRaises(RUNTIME.PreparationError):
                        RUNTIME.download_artifact(self.record, self.root / "download.part", pause=lambda delay: self.fail("Integrity failures must not retry"))
                self.assertEqual(len(opener.requests), 1)
                self.assertFalse((self.root / "download.part").exists())

    def test_redirect_handler_and_changed_response_origin_are_rejected(self):
        with self.assertRaises(RUNTIME.PreparationError):
            RUNTIME.NoRedirects().redirect_request(None, None, 302, "synthetic", {}, "https://example.org/elsewhere")
        opener = Opener([Response(self.payload, url="https://example.org/elsewhere")])
        with patch.object(RUNTIME, "build_opener", return_value=opener):
            with self.assertRaises(RUNTIME.PreparationError):
                RUNTIME.download_artifact(self.record, self.root / "download.part", pause=lambda delay: self.fail("Redirect must not retry"))
        self.assertEqual(len(opener.requests), 1)
        self.assertFalse((self.root / "download.part").exists())

    def test_attempt_deadline_retries_only_to_fixed_limit_and_removes_partial_files(self):
        opener = Opener([self.payload, self.payload, self.payload])
        delays = []
        with patch.object(RUNTIME, "build_opener", return_value=opener), \
                patch.object(RUNTIME.time, "monotonic", side_effect=[0, 91, 100, 191, 200, 291]):
            with self.assertRaises(RUNTIME.PreparationError):
                RUNTIME.download_artifact(self.record, self.root / "download.part", pause=delays.append)
        self.assertEqual(len(opener.requests), 3)
        self.assertEqual(delays, [1, 2])
        self.assertFalse((self.root / "download.part").exists())

    def test_cli_error_does_not_print_raw_network_failure(self):
        pin = self.root / "pin.json"
        pin.write_text(json.dumps(self.record))
        stderr = io.StringIO()
        marker = "SYNTHETIC_PROXY_PASSWORD_381549"
        with patch.object(RUNTIME, "PIN", pin), \
                patch.object(RUNTIME, "prepare_runtime", side_effect=URLError(marker)), \
                patch("sys.argv", ["prepare-ocr-runtime.py", "--download"]), \
                patch("sys.stderr", stderr):
            with self.assertRaises(SystemExit) as captured:
                RUNTIME.main()
        self.assertEqual(captured.exception.code, 1)
        self.assertNotIn(marker, stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
