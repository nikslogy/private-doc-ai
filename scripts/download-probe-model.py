#!/usr/bin/env python3
"""Explicit CI-only download of the reviewed public experimental model; no private data input."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import time
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--download", action="store_true", required=True)
    parser.add_argument("--accept-license", action="store_true", required=True)
    args = parser.parse_args()
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        raise ValueError("This downloader requires the explicitly invoked disposable GitHub runner")
    catalogue = json.loads((ROOT / "config/models.json").read_text())
    models = [m for m in catalogue["models"] if m["id"] == args.model_id]
    if len(models) != 1:
        raise ValueError("Expected one reviewed experimental model")
    model = models[0]
    if model.get("assessment") != "experimental" or model["license"]["identifier"] != "Apache-2.0":
        raise ValueError("This explicit experiment is limited to the reviewed public Apache-2.0 candidate")
    revision = model["revision"]
    digest = model["sha256"]
    count = model["byteCount"]
    import re
    if not re.fullmatch(r"[a-f0-9]{40}", revision) or not re.fullmatch(r"[a-f0-9]{64}", digest):
        raise ValueError("Reviewed immutable identity is missing")
    if not 0 < count <= 4 * 1024**3 or model["runtimeVersion"] != catalogue["runtime"]["version"]:
        raise ValueError("Unsupported reviewed model bounds")
    url = model["downloadUrl"]
    parsed = urlparse(url)
    expected_path = f"/litert-community/Qwen2.5-1.5B-Instruct/resolve/{revision}/{model['fileName']}"
    if (parsed.scheme != "https" or parsed.hostname != "huggingface.co" or parsed.path != expected_path
            or parsed.username or parsed.password or parsed.fragment):
        raise ValueError("Expected the pinned public upstream file URL")
    output = args.output.absolute()
    if output.exists() or output.is_symlink() or output.parent.is_symlink():
        raise ValueError("Refusing an existing or symbolic model destination")
    if output.parent.resolve() != output.parent:
        raise ValueError("Model destination parent must be canonical")
    if shutil.disk_usage(output.parent).free < count + 256 * 1024**2:
        raise ValueError("Insufficient scratch disk for the verified model")
    part = output.with_name(output.name + ".part")
    if part.exists() or part.is_symlink():
        raise ValueError("Refusing an existing partial model destination")
    started = time.monotonic()
    sha = hashlib.sha256()
    received = 0
    try:
        request = Request(url, headers={"Accept-Encoding": "identity", "User-Agent": "private-doc-ai-model-probe/1"})
        with urlopen(request, timeout=60) as source, part.open("xb") as sink:
            os.chmod(part, 0o600)
            length = source.headers.get("Content-Length")
            if length is not None and int(length) != count:
                raise ValueError("Upstream byte count differs from the reviewed artifact")
            while True:
                if time.monotonic() - started > 1200:
                    raise ValueError("Public model download deadline exceeded")
                block = source.read(1024 * 1024)
                if not block:
                    break
                received += len(block)
                if received > count:
                    raise ValueError("Upstream model exceeds reviewed size")
                sha.update(block)
                sink.write(block)
            sink.flush()
            os.fsync(sink.fileno())
        if received != count or sha.hexdigest() != digest:
            raise ValueError("Public model size or SHA-256 verification failed")
        part.rename(output)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({
            "schemaVersion": 1, "modelId": model["id"], "revision": revision,
            "byteCount": received, "sha256": sha.hexdigest(), "runtimeVersion": model["runtimeVersion"],
            "sourceUrl": url, "licenseIdentifier": model["license"]["identifier"],
            "scope": "Verified public model bytes only; no runtime or document accuracy result implied",
        }, indent=2) + "\n")
        print(f"Verified public model bytes: {received}; SHA-256 {sha.hexdigest()}")
    finally:
        part.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Do not emit redirect URLs, provider error pages or accidental credential-bearing messages.
        print(f"Public model download failed ({type(error).__name__}); no model result is claimed", file=sys.stderr)
        raise SystemExit(1)
