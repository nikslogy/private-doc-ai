#!/usr/bin/env python3
"""Verify the exact reviewed public OCR AAR resolved by Gradle before use."""

import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
COORDINATE = "com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.9.0"
URL = "https://jitpack.io/com/github/adaptech-cz/Tesseract4Android/tesseract4android/4.9.0/tesseract4android-4.9.0.aar"


def verify_artifact(record, path):
    if not isinstance(record, dict) or set(record) != {"schemaVersion", "coordinate", "sourceRevision", "artifact"}:
        raise ValueError("Invalid OCR runtime pin fields")
    if type(record["schemaVersion"]) is not int or record["schemaVersion"] != 1 or record["coordinate"] != COORDINATE:
        raise ValueError("Unreviewed OCR coordinate/schema")
    if record["sourceRevision"] != "15c534717b1cb58261b58d4e4c1200c7f81f668c":
        raise ValueError("Unreviewed OCR source revision")
    artifact = record["artifact"]
    if not isinstance(artifact, dict) or set(artifact) != {"url", "byteCount", "sha256"} or artifact["url"] != URL:
        raise ValueError("Unreviewed OCR artifact source")
    if type(artifact["byteCount"]) is not int or not 0 < artifact["byteCount"] <= 64 * 1024**2:
        raise ValueError("Invalid OCR artifact byte count")
    if not isinstance(artifact["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"]):
        raise ValueError("Invalid OCR artifact SHA-256")
    path = Path(path)
    if not path.is_file() or path.stat().st_size != artifact["byteCount"]:
        raise ValueError("Resolved OCR AAR missing or byte count differs from reviewed pin")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(64 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != artifact["sha256"]:
        raise ValueError("Resolved OCR AAR SHA-256 differs from reviewed bytes; nothing may build")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: scripts/verify-ocr-dependency.py <Gradle-resolved-tesseract.aar>")
    try:
        record = json.loads((ROOT / "config/ocr-runtime.json").read_text())
        verify_artifact(record, sys.argv[1])
    except (OSError, ValueError) as failure:
        raise SystemExit(f"OCR dependency verification failed: {failure}")
    print("Verified exact reviewed Tesseract4Android 4.9.0 AAR byte count and SHA-256.")


if __name__ == "__main__":
    main()
