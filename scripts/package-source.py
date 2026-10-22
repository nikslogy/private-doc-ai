#!/usr/bin/env python3
"""Package this repository's source and synthetic fixtures, never local tooling/caches."""

import argparse
import hashlib
import json
from pathlib import Path
import stat
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DIRECTORIES = ("app", "core", "platform", "config", "docs", "gradle", "scripts", "test-fixtures", ".github")
ROOT_FILES = ("README.md", "AGENTS.md", ".gitignore", "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew")
EXCLUDED_DIRECTORIES = {"build", ".gradle", ".kotlin", "__pycache__", ".tooling", ".toolchains"}
EXCLUDED_SUFFIXES = {".pyc", ".jks", ".keystore", ".p12", ".litertlm", ".traineddata", ".tflite", ".gguf", ".apk", ".aar"}


def package(output: Path, verification: dict | None) -> None:
    candidates = [ROOT / name for name in ROOT_FILES]
    for name in DIRECTORIES:
        candidates.extend((ROOT / name).rglob("*"))
    files = []
    for path in candidates:
        relative = path.relative_to(ROOT)
        if any(part in EXCLUDED_DIRECTORIES for part in relative.parts):
            continue
        if path.is_symlink():
            raise ValueError(f"Source symlink requires review: {relative}")
        if path.is_file() and path.suffix not in EXCLUDED_SUFFIXES and path.name != ".env":
            files.append(path)
    files = sorted(set(files), key=lambda path: path.relative_to(ROOT).as_posix())
    entries = [
        {"path": path.relative_to(ROOT).as_posix(), "bytes": path.stat().st_size,
         "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        for path in files
    ]
    fingerprint = hashlib.sha256(json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    manifest = {"schemaVersion": 1, "sourceManifestSha256": fingerprint, "files": entries,
                "verification": verification if verification is not None else {"recordProvided": False}}
    manifest_bytes = (json.dumps(manifest, indent=2) + "\n").encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".zip.part")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for relative, data, executable in [
                (entry["path"], path.read_bytes(), path.suffix == ".sh" or path.name == "gradlew")
                for entry, path in zip(entries, files)
            ] + [("SOURCE_MANIFEST.json", manifest_bytes, False)]:
                info = zipfile.ZipInfo("private-document-assistant/" + relative, date_time=(2026, 10, 18, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (stat.S_IFREG | (0o755 if executable else 0o644)) << 16
                archive.writestr(info, data)
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None:
                raise ValueError("Source archive integrity failure")
            for entry, path in zip(entries, files):
                data = archive.read("private-document-assistant/" + entry["path"])
                if hashlib.sha256(data).hexdigest() != entry["sha256"] or data != path.read_bytes():
                    raise ValueError(f"Source changed during packaging: {entry['path']}")
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    (output.parent / "SOURCE_MANIFEST.json").write_bytes(manifest_bytes)
    checksum = hashlib.sha256(output.read_bytes()).hexdigest()
    checksum_line = f"{checksum}  {output.name}\n"
    output.with_suffix(".zip.sha256").write_text(checksum_line)
    (output.parent / "SHA256SUMS").write_text(checksum_line)
    print(f"Packaged {len(files)} verified source files: {output} ({output.stat().st_size} bytes)")
    print(f"SHA-256 {checksum}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/private-document-assistant-source.zip")
    parser.add_argument("--verification-record", type=Path, help="Optional JSON record of checks actually executed")
    args = parser.parse_args()
    verification = json.loads(args.verification_record.read_text()) if args.verification_record else None
    if verification is not None and not isinstance(verification, dict):
        parser.error("verification record must be a JSON object")
    package(args.output.resolve(), verification)


if __name__ == "__main__":
    main()
