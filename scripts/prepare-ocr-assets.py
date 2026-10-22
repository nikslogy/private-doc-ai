#!/usr/bin/env python3
"""Prepare public, pinned OCR models in ignored build assets; never process documents."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, HTTPSHandler, ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parents[1]
CATALOGUE = ROOT / "config/ocr-models.json"
DESTINATION = ROOT / "platform/build/generated/ocrAssets"
LANGUAGES = {"eng", "hin", "mar"}
MAX_MODEL_BYTES = 16 * 1024 * 1024
REPOSITORY = "https://github.com/tesseract-ocr/tessdata_fast"


class AssetError(ValueError):
    pass


def exact_keys(value, keys, label):
    if not isinstance(value, dict) or set(value) != set(keys):
        raise AssetError(f"Invalid {label} fields")


def validate_catalogue(catalogue):
    exact_keys(catalogue, {"schemaVersion", "id", "engine", "repository", "revision", "license", "models"}, "catalogue")
    if type(catalogue["schemaVersion"]) is not int or catalogue["schemaVersion"] != 1:
        raise AssetError("Unsupported OCR catalogue schema")
    if catalogue["id"] != "tessdata-fast-eng-hin-mar-v1" or catalogue["repository"] != REPOSITORY:
        raise AssetError("Unknown OCR source")
    if catalogue["engine"] != {"id": "tesseract4android", "version": "4.9.0", "nativeVersion": "5.5.1"}:
        raise AssetError("Unreviewed OCR runtime")
    revision = catalogue["revision"]
    if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise AssetError("OCR revision must be an immutable Git commit")
    base_url = f"https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/{revision}/"
    if catalogue["license"] != {"identifier": "Apache-2.0", "url": base_url + "LICENSE"}:
        raise AssetError("OCR licence must identify the reviewed source revision")
    models = catalogue["models"]
    if not isinstance(models, list) or len(models) != len(LANGUAGES):
        raise AssetError("Expected exactly the reviewed English, Hindi and Marathi models")
    seen = set()
    for model in models:
        exact_keys(model, {"language", "fileName", "byteCount", "sha256", "url"}, "model")
        language = model["language"]
        if not isinstance(language, str) or language not in LANGUAGES or language in seen:
            raise AssetError("Unknown or duplicate OCR language")
        seen.add(language)
        name = f"{language}.traineddata"
        if model["fileName"] != name or model["url"] != base_url + name:
            raise AssetError("OCR filename/URL must identify the exact reviewed source")
        if type(model["byteCount"]) is not int or not 0 < model["byteCount"] <= MAX_MODEL_BYTES:
            raise AssetError("Invalid OCR model byte count")
        if not isinstance(model["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", model["sha256"]):
            raise AssetError("Missing actual OCR model SHA-256")
    return catalogue


def catalogue_bytes(catalogue):
    return (json.dumps(catalogue, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def reject_symlinks(path):
    """Do not follow an existing symlink in any output path component."""
    for component in (path, *path.parents):
        if component.is_symlink():
            raise AssetError("OCR output path contains a symlink")


def verify_file(path, model):
    reject_symlinks(path)
    if not path.is_file() or path.stat().st_size != model["byteCount"]:
        raise AssetError(f"OCR asset missing or wrong size: {model['fileName']}")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(64 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != model["sha256"]:
        raise AssetError(f"OCR asset SHA-256 mismatch: {model['fileName']}")


def verify_assets(catalogue, destination):
    reject_symlinks(destination)
    catalogue_path = destination / "ocr/catalogue.json"
    reject_symlinks(catalogue_path)
    if not catalogue_path.is_file() or catalogue_path.read_bytes() != catalogue_bytes(catalogue):
        raise AssetError("Packaged OCR catalogue missing or differs from reviewed source")
    expected = {"ocr/catalogue.json"} | {f"ocr/tessdata/{model['fileName']}" for model in catalogue["models"]}
    actual = set()
    for entry in destination.rglob("*"):
        if entry.is_symlink():
            raise AssetError("OCR assets contain a symlink")
        if entry.is_file():
            actual.add(entry.relative_to(destination).as_posix())
        elif not entry.is_dir():
            raise AssetError("OCR assets contain a special file")
    if actual != expected:
        raise AssetError("Unexpected or missing generated OCR assets")
    for model in catalogue["models"]:
        verify_file(destination / "ocr/tessdata" / model["fileName"], model)


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise AssetError("OCR download redirect rejected; review the pinned source")


def download_model(model, destination):
    # ProxyHandler retains the session's configured proxy. System CA trust remains
    # in force; no certificate checks, proxy or network policy are bypassed.
    opener = build_opener(ProxyHandler(), HTTPSHandler(context=ssl.create_default_context()), NoRedirects())
    size = 0
    digest = hashlib.sha256()
    deadline = time.monotonic() + 180
    with opener.open(model["url"], timeout=45) as response, destination.open("xb") as output:
        if response.status != 200 or response.geturl() != model["url"]:
            raise AssetError("Unexpected OCR download response")
        while True:
            if time.monotonic() > deadline:
                raise AssetError("OCR download exceeded its time budget")
            block = response.read(min(64 * 1024, model["byteCount"] + 1 - size))
            if not block:
                break
            size += len(block)
            if size > model["byteCount"]:
                raise AssetError("OCR download exceeds its pinned byte count")
            output.write(block)
            digest.update(block)
        output.flush()
        os.fsync(output.fileno())
    if size != model["byteCount"] or digest.hexdigest() != model["sha256"]:
        raise AssetError("OCR download size or SHA-256 mismatch; no asset published")


def prepare_assets(catalogue, destination, download=False, fetch=download_model):
    validate_catalogue(catalogue)
    destination = Path(destination).absolute()
    reject_symlinks(destination)
    if destination.exists() and not destination.is_dir():
        raise AssetError("OCR output exists and is not a directory")
    try:
        verify_assets(catalogue, destination)
        return
    except AssetError:
        if not download:
            raise
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".ocr-assets-", dir=destination.parent))
    previous = destination.with_name(destination.name + ".previous")
    try:
        (staging / "ocr/tessdata").mkdir(parents=True)
        for model in catalogue["models"]:
            target = staging / "ocr/tessdata" / model["fileName"]
            existing = destination / "ocr/tessdata" / model["fileName"]
            try:
                verify_file(existing, model)
                shutil.copyfile(existing, target)
            except AssetError:
                fetch(model, target)
            verify_file(target, model)
        (staging / "ocr/catalogue.json").write_bytes(catalogue_bytes(catalogue))
        verify_assets(catalogue, staging)
        reject_symlinks(destination)
        reject_symlinks(previous)
        if previous.exists():
            raise AssetError("Previous OCR publication exists; inspect it before retrying")
        had_previous = destination.exists()
        if had_previous:
            destination.rename(previous)
        try:
            staging.rename(destination)
        except BaseException:
            if had_previous:
                previous.rename(destination)
            raise
        if had_previous:
            shutil.rmtree(previous)
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--download", action="store_true", help="Fetch only missing/invalid public OCR assets, verifying before publication")
    mode.add_argument("--verify-only", action="store_true", help="No network; fail unless exact reviewed generated assets exist")
    args = parser.parse_args()
    try:
        catalogue = validate_catalogue(json.loads(CATALOGUE.read_text(encoding="utf-8")))
        prepare_assets(catalogue, DESTINATION, args.download)
    except (AssetError, OSError, HTTPError, json.JSONDecodeError) as failure:
        parser.exit(1, f"OCR assets unavailable: {failure}\n")
    print("Verified pinned public OCR assets (English, Hindi, Marathi); no documents or AI model were downloaded.")


if __name__ == "__main__":
    main()
