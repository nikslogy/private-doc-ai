#!/usr/bin/env python3
"""Fetch/inspect public OCR build inputs, never execute them or process documents.

This discovery probe records actual bytes and source comparisons. It is not a
substitute for the pinned production dependency check after review.
"""

import hashlib
import importlib.util
import json
from pathlib import Path
import re
import ssl
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, HTTPSHandler, ProxyHandler, build_opener
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "artifacts/ocr-provenance"
BASE = "https://jitpack.io/com/github/adaptech-cz/Tesseract4Android/tesseract4android/4.9.0/tesseract4android-4.9.0"
REVISION = "15c534717b1cb58261b58d4e4c1200c7f81f668c"
TESSDATA_REVISION = "87416418657359cb625c412a48b6e1d6d41c29bd"
TESSDATA_BASE = f"https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/{TESSDATA_REVISION}/"
UPSTREAM = f"https://raw.githubusercontent.com/adaptech-cz/Tesseract4Android/{REVISION}/tesseract4android/src/main/java/com/googlecode/tesseract/android/TessBaseAPI.java"


class PublicRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        parsed = urlsplit(new_url)
        if parsed.scheme != "https" or parsed.hostname != "jitpack.io" or parsed.username or parsed.password:
            raise ValueError("Unexpected public OCR repository redirect")
        return super().redirect_request(request, fp, code, message, headers, new_url)


def download(url, name, limit):
    output = OUTPUT / name
    temporary = output.with_suffix(output.suffix + ".part")
    opener = build_opener(ProxyHandler(), HTTPSHandler(context=ssl.create_default_context()), PublicRedirects())
    total = 0
    digest = hashlib.sha256()
    deadline = time.monotonic() + 180
    try:
        with opener.open(url, timeout=45) as response, temporary.open("xb") as target:
            if response.status != 200:
                raise ValueError("Unexpected public OCR HTTP response")
            while True:
                if time.monotonic() > deadline:
                    raise ValueError("Public OCR download exceeded its time budget")
                block = response.read(64 * 1024)
                if not block:
                    break
                total += len(block)
                if total > limit:
                    raise ValueError("Public OCR input exceeded bounded download size")
                digest.update(block)
                target.write(block)
        temporary.replace(output)
        return {"url": url, "fileName": name, "byteCount": total, "sha256": digest.hexdigest()}
    finally:
        temporary.unlink(missing_ok=True)


def archive_members(path, native):
    with path.open("rb") as source:
        native.zip_directory(source)
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        total = 0
        seen = set()
        for entry in entries:
            native.safe_member(entry)
            total += entry.file_size
            if total > 256 * 1024**2 or entry.filename in seen:
                raise ValueError("OCR archive duplicates or expanded size exceed bounds")
            seen.add(entry.filename)
        yield archive


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if OUTPUT.is_symlink() or any(parent.is_symlink() for parent in OUTPUT.parents):
        raise ValueError("OCR provenance output contains a symlink")
    spec = importlib.util.spec_from_file_location("native_inventory", ROOT / "scripts/check-native-libraries.py")
    native = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = native
    spec.loader.exec_module(native)
    report = {"schemaVersion": 1, "probeOnly": True, "coordinate": "com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.9.0",
              "sourceRevision": REVISION, "inputs": [], "nativeLibraries": []}
    try:
        report["tessdataRevision"] = TESSDATA_REVISION
        report["models"] = []
        for language in ("eng", "hin", "mar"):
            item = download(TESSDATA_BASE + language + ".traineddata", language + ".traineddata", 16 * 1024**2)
            item["language"] = language
            report["models"].append(item)
        report["modelLicense"] = download(TESSDATA_BASE + "LICENSE", "LICENSE.tessdata.txt", 64 * 1024)
        report["modelLicenseFirstLines"] = (OUTPUT / "LICENSE.tessdata.txt").read_text().splitlines()[:10]
        for suffix, limit in ((".pom", 1024**2), (".aar", 64 * 1024**2)):
            report["inputs"].append(download(BASE + suffix, "tesseract4android-4.9.0" + suffix, limit))
        pom_path = OUTPUT / "tesseract4android-4.9.0.pom"
        pom = ET.fromstring(pom_path.read_bytes())
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        report["pomDependencies"] = [
            {field: dependency.findtext("m:" + field, namespaces=namespace) for field in ("groupId", "artifactId", "version", "scope")}
            for dependency in pom.findall("m:dependencies/m:dependency", namespace)
        ]
        for archive in archive_members(OUTPUT / "tesseract4android-4.9.0.aar", native):
            manifest = archive.read("AndroidManifest.xml")
            if len(manifest) > 64 * 1024:
                raise ValueError("OCR AAR manifest exceeds limit")
            report["manifest"] = manifest.decode("utf-8")
            ET.fromstring(manifest)
            for entry in archive.infolist():
                match = re.fullmatch(r"jni/(arm64-v8a|x86_64|armeabi-v7a|x86)/(lib[A-Za-z0-9_.+\-]+\.so)", entry.filename)
                if not match:
                    continue
                abi, name = match.groups()
                payload = archive.read(entry)
                segments, alignment = native.inspect_elf(payload, abi)
                report["nativeLibraries"].append({"abi": abi, "name": name, "byteCount": len(payload),
                    "sha256": hashlib.sha256(payload).hexdigest(), "loadSegments": segments, "minimumAlignment": alignment})
            if not report["nativeLibraries"]:
                raise ValueError("No actual native OCR library found")
        try:
            sources = download(BASE + "-sources.jar", "tesseract4android-4.9.0-sources.jar", 8 * 1024**2)
            report["inputs"].append(sources)
            upstream = download(UPSTREAM, "upstream-TessBaseAPI.java", 1024**2)
            report["inputs"].append(upstream)
            for archive in archive_members(OUTPUT / "tesseract4android-4.9.0-sources.jar", native):
                api = archive.read("com/googlecode/tesseract/android/TessBaseAPI.java")
                report["apiJavaMatchesImmutableUpstream"] = api == (OUTPUT / "upstream-TessBaseAPI.java").read_bytes()
                report["sourceApiSha256"] = hashlib.sha256(api).hexdigest()
        except HTTPError as failure:
            if failure.code != 404:
                raise
            report["sourcesUnavailable"] = True
        report["inspectionPassed"] = True
    except BaseException:
        report["inspectionPassed"] = False
        raise
    finally:
        serialized = json.dumps(report, indent=2) + "\n"
        (OUTPUT / "PROVENANCE.json").write_text(serialized)
        print(serialized)


if __name__ == "__main__":
    main()
