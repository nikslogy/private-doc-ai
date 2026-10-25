#!/usr/bin/env python3
"""Verify/cache the exact public OCR AAR for artifact-only local Maven resolution."""

import argparse
import hashlib
import importlib.util
from itertools import islice
import json
import os
from pathlib import Path
import re
import shutil
import socket
import ssl
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, HTTPSHandler, ProxyHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
PIN = ROOT / "config/ocr-runtime.json"
REPOSITORY = ROOT / ".tooling/verified-ocr-maven"
GRADLE_USER_HOME = ROOT / ".tooling/gradle-user-home"
ARTIFACT_PATH = Path("com/github/adaptech-cz/Tesseract4Android/tesseract4android/4.9.0/tesseract4android-4.9.0.aar")
GRADLE_COORDINATE_PATH = Path("caches/modules-2/files-2.1/com.github.adaptech-cz.Tesseract4Android/tesseract4android/4.9.0")
MAX_GRADLE_HASH_DIRECTORIES = 32
ATTEMPTS = 3
SOCKET_TIMEOUT_SECONDS = 30
ATTEMPT_BUDGET_SECONDS = 90

SPEC = importlib.util.spec_from_file_location("ocr_dependency_pin", Path(__file__).with_name("verify-ocr-dependency.py"))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class PreparationError(ValueError):
    pass


def validate_pin(record):
    """Validate the source before any request; the existing verifier checks bytes again."""
    if (not isinstance(record, dict)
            or set(record) != {"schemaVersion", "coordinate", "sourceRevision", "artifact"}
            or type(record["schemaVersion"]) is not int or record["schemaVersion"] != 1
            or record["coordinate"] != VERIFIER.COORDINATE
            or record["sourceRevision"] != "15c534717b1cb58261b58d4e4c1200c7f81f668c"):
        raise PreparationError("Unreviewed OCR runtime identity")
    artifact = record["artifact"]
    if (not isinstance(artifact, dict) or set(artifact) != {"url", "byteCount", "sha256"}
            or artifact["url"] != VERIFIER.URL
            or type(artifact["byteCount"]) is not int or not 0 < artifact["byteCount"] <= 64 * 1024**2
            or not isinstance(artifact["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", artifact["sha256"])):
        raise PreparationError("Unreviewed OCR runtime artifact pin")
    return record


def reject_symlinks(path):
    for component in (path, *path.parents):
        if component.is_symlink():
            raise PreparationError("OCR runtime cache path contains a symlink")


def verify_cached(record, path):
    reject_symlinks(path)
    VERIFIER.verify_artifact(record, path)


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise PreparationError("OCR runtime redirect rejected; review the pinned origin")


def transient_failure(failure):
    if isinstance(failure, HTTPError):
        return failure.code in (404, 429) or 500 <= failure.code <= 599
    reason = failure.reason if isinstance(failure, URLError) else failure
    if isinstance(reason, ssl.SSLError):
        return False
    return (isinstance(reason, (TimeoutError, ConnectionResetError, ConnectionAbortedError, ConnectionRefusedError))
            or isinstance(reason, socket.gaierror) and reason.errno == socket.EAI_AGAIN)


def safe_transport_category(failure):
    """Fixed diagnostic tokens only: never provider text, URLs or proxy credentials."""
    if isinstance(failure, HTTPError):
        code = failure.code
        return f"HTTP_{code}" if type(code) is int and 100 <= code <= 599 else "HTTPError"
    reason = failure.reason if isinstance(failure, URLError) else failure
    for kind in (ssl.SSLCertVerificationError, ssl.SSLError, TimeoutError,
                 ConnectionResetError, ConnectionAbortedError, ConnectionRefusedError, socket.gaierror):
        if isinstance(reason, kind):
            return kind.__name__
    return "URLError" if isinstance(failure, URLError) else "OSError"


def copy_verified_gradle_artifact(record, destination, gradle_user_home):
    """Import only the reviewed AAR from the exact existing Gradle coordinate."""
    home = Path(gradle_user_home).absolute()
    coordinate = home / GRADLE_COORDINATE_PATH
    try:
        # Do not follow an ancestor alias or inspect unrelated cache coordinates.
        reject_symlinks(coordinate)
        if home.resolve() != home or not coordinate.is_dir():
            return False
        candidates = list(islice(coordinate.iterdir(), MAX_GRADLE_HASH_DIRECTORIES + 1))
        if len(candidates) > MAX_GRADLE_HASH_DIRECTORIES:
            return False
    except (OSError, ValueError):
        return False
    for directory in sorted(candidates):
        if not re.fullmatch(r"[a-f0-9]{40}", directory.name):
            continue
        source = directory / ARTIFACT_PATH.name
        created = False
        try:
            reject_symlinks(source)
            if not directory.is_dir() or not source.is_file() or source.absolute() != source.resolve():
                continue
            verify_cached(record, source)
            # A bounded second copy plus final verification prevents a changed cache
            # file from publishing different bytes after its first successful hash.
            received = 0
            with source.open("rb") as input_file, destination.open("xb") as output_file:
                created = True
                while True:
                    block = input_file.read(min(64 * 1024, record["artifact"]["byteCount"] + 1 - received))
                    if not block:
                        break
                    received += len(block)
                    if received > record["artifact"]["byteCount"]:
                        raise PreparationError("Gradle OCR cache entry changed size")
                    output_file.write(block)
                output_file.flush()
                os.fsync(output_file.fileno())
            verify_cached(record, destination)
            return True
        except (OSError, ValueError):
            if created:
                destination.unlink(missing_ok=True)
            # Cache misses/corruption never weaken verification or change origin.
            continue
    return False


def download_artifact(record, destination, pause=time.sleep):
    validate_pin(record)
    reject_symlinks(destination)
    if destination.exists():
        raise PreparationError("OCR download staging file already exists")
    artifact = record["artifact"]
    # Retain inherited proxy routing and system CA trust. Redirects, HTTP fallback,
    # alternate origins and certificate verification bypasses are not permitted.
    opener = build_opener(ProxyHandler(), HTTPSHandler(context=ssl.create_default_context()), NoRedirects())
    for attempt in range(ATTEMPTS):
        created = False
        try:
            digest = hashlib.sha256()
            count = 0
            deadline = time.monotonic() + ATTEMPT_BUDGET_SECONDS
            request = Request(artifact["url"], method="GET", headers={
                "User-Agent": "private-doc-ai-verified-ocr-bootstrap/1",
                "Accept-Encoding": "identity",
                "Cache-Control": "no-cache",
            })
            with opener.open(request, timeout=SOCKET_TIMEOUT_SECONDS) as response:
                if response.status != 200 or response.geturl() != artifact["url"]:
                    raise PreparationError("Unexpected OCR runtime download response")
                with destination.open("xb") as output:
                    created = True
                    while True:
                        if time.monotonic() > deadline:
                            raise TimeoutError("OCR runtime attempt deadline")
                        block = response.read(min(64 * 1024, artifact["byteCount"] + 1 - count))
                        if not block:
                            break
                        count += len(block)
                        if count > artifact["byteCount"]:
                            raise PreparationError("OCR runtime download exceeds the pinned size")
                        digest.update(block)
                        output.write(block)
                    output.flush()
                    os.fsync(output.fileno())
            if count != artifact["byteCount"] or digest.hexdigest() != artifact["sha256"]:
                raise PreparationError("OCR runtime size or SHA-256 mismatch; no artifact published")
            return
        except (HTTPError, URLError, OSError) as failure:
            if created:
                destination.unlink(missing_ok=True)
            if not transient_failure(failure) or attempt + 1 == ATTEMPTS:
                category = safe_transport_category(failure)
                raise PreparationError(f"Pinned OCR runtime download unavailable ({category}); no artifact published") from None
            # Fixed bounded delays ignore untrusted Retry-After/redirect headers.
            pause(attempt + 1)
        except BaseException:
            if created:
                destination.unlink(missing_ok=True)
            raise


def prepare_runtime(record, repository=REPOSITORY, download=False, fetch=download_artifact,
                    gradle_user_home=GRADLE_USER_HOME):
    validate_pin(record)
    repository = Path(repository).absolute()
    destination = repository / ARTIFACT_PATH
    reject_symlinks(destination)
    if destination.exists() and not destination.is_file():
        raise PreparationError("OCR runtime cache entry is not a regular file")
    try:
        verify_cached(record, destination)
        return destination
    except ValueError:
        if not download:
            raise PreparationError("Exact verified OCR runtime is absent from the local cache") from None
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    reject_symlinks(destination)
    staging = Path(tempfile.mkdtemp(prefix=".ocr-runtime-", dir=destination.parent))
    staged = staging / "artifact.aar"
    try:
        if not copy_verified_gradle_artifact(record, staged, gradle_user_home):
            fetch(record, staged)
        # Reuse the mandatory Gradle-input verifier, independently of fetch code.
        verify_cached(record, staged)
        os.chmod(staged, 0o444)
        reject_symlinks(destination)
        if destination.exists() and not destination.is_file():
            raise PreparationError("OCR runtime cache entry changed type")
        os.replace(staged, destination)
        directory_fd = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
        verify_cached(record, destination)
        return destination
    finally:
        shutil.rmtree(staging)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--download", action="store_true", help="GET the exact public AAR only if missing/invalid; verify before publication")
    mode.add_argument("--verify-only", action="store_true", help="No network; require the exact reviewed cached AAR")
    args = parser.parse_args()
    try:
        record = json.loads(PIN.read_text(encoding="utf-8"))
        prepare_runtime(record, download=args.download)
    except PreparationError as failure:
        parser.exit(1, f"OCR runtime unavailable: {failure}\n")
    except (OSError, ValueError):
        # Filesystem/proxy exception strings can contain local details or credentials.
        parser.exit(1, "OCR runtime unavailable: verification or storage failed; no unverified artifact accepted.\n")
    print("Verified exact pinned public Tesseract4Android AAR in the ignored local Maven cache.")


if __name__ == "__main__":
    main()
