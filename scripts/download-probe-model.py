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
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]

# Exact public identities reviewed independently of the mutable catalogue. An
# additional model/revision requires another source/licence/hash review here.
REVIEWED_MODELS = {
    "qwen25-15b-q8-legacy": (
        "litert-community/Qwen2.5-1.5B-Instruct",
        "19edb84c69a0212f29a6ef17ba0d6f278b6a1614",
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        1_597_931_520,
        "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9",
        "0.9.0",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct/blob/2fd50615a2a9792d223eba8e0741aa90ef21a869/LICENSE",
    ),
    "qwen3-06b-q4-nothink": (
        "litert-community/Qwen3-0.6B-int4",
        "256f33043931947c173d7f3cc1d84ee7a8538cc2",
        "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
        347_251_840,
        "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139",
        "0.16.0",
        "https://huggingface.co/Qwen/Qwen3-0.6B/blob/c1899de289a04d12100db370d81485cdf75e47ca/LICENSE",
    ),
}


def validate_reviewed_model(catalogue: dict, model_id: str) -> dict:
    """Admit only an exact reviewed public artifact for the selected runtime."""
    if model_id not in REVIEWED_MODELS:
        raise ValueError("The public-model identity has not been reviewed")
    models = [m for m in catalogue.get("models", []) if m.get("id") == model_id]
    if len(models) != 1:
        raise ValueError("Expected one reviewed experimental model")
    model = models[0]
    repository, revision, filename, count, digest, runtime, terms_url = REVIEWED_MODELS[model_id]
    if (catalogue.get("schemaVersion") != 1
            or catalogue.get("runtime") != {"id": "litert-lm", "version": runtime, "format": "litertlm"}
            or model.get("assessment") != "experimental"
            or model.get("format") != "litertlm"
            or model.get("runtimeVersion") != runtime
            or model.get("revision") != revision or model.get("fileName") != filename
            or model.get("byteCount") != count or model.get("sha256") != digest
            or "CPU" not in model.get("backends", [])
            or model.get("evaluatedLanguages") != [] or model.get("evaluatedTasks") != []
            or model.get("license") != {"identifier": "Apache-2.0", "termsUrl": terms_url, "requiresAcceptance": True}):
        raise ValueError("The public-model metadata differs from its reviewed identity/runtime/licence")
    expected_url = f"https://huggingface.co/{repository}/resolve/{revision}/{filename}?download=true"
    if model.get("downloadUrl") != expected_url:
        raise ValueError("Expected the exact pinned public upstream file URL")
    return model


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
    model = validate_reviewed_model(catalogue, args.model_id)
    revision = model["revision"]
    digest = model["sha256"]
    count = model["byteCount"]
    url = model["downloadUrl"]
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
