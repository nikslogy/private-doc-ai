#!/usr/bin/env python3
"""Decode the supplied signing secret in runner temp and verify its public identity.

Never prints credentials, caches a key, or uploads it. The workflow owns cleanup.
"""

import base64
import hashlib
import importlib.util
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    path = None
    created = False
    try:
        if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REF") != "refs/heads/main":
            raise ValueError("Stable signing preparation requires the main-branch GitHub runner")
        spec = importlib.util.spec_from_file_location("staging", ROOT / "scripts/stage-apks.py")
        staging = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(staging)
        record = staging.stable_signing_record()
        runner_temp = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
        path = Path(os.environ["ANDROID_DEV_KEYSTORE_PATH"])
        parent = path.parent
        if (parent.is_symlink() or parent.resolve(strict=True).parent != runner_temp or
            not parent.name.startswith("private-assistant-signing.") or path.name != "development.p12" or
            path.exists() or path.is_symlink()):
            raise ValueError("Signing destination is not a fresh runner temporary path")
        encoded = os.environ.get("ANDROID_DEV_KEYSTORE_BASE64", "")
        if not encoded or len(encoded) > 64 * 1024 or not os.environ.get("ANDROID_DEV_STORE_PASSWORD"):
            raise ValueError("Both development signing secrets are required")
        payload = base64.b64decode(encoded.strip(), validate=True)
        if not 1024 <= len(payload) <= 32 * 1024:
            raise ValueError("Invalid signing-store size")
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        created = True
        with os.fdopen(descriptor, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        result = subprocess.run([
            "keytool", "-exportcert", "-storetype", "PKCS12", "-alias", record["alias"],
            "-keystore", str(path), "-storepass:env", "ANDROID_DEV_STORE_PASSWORD",
        ], capture_output=True, timeout=30)
        if result.returncode or hashlib.sha256(result.stdout).hexdigest() != record["certificateSha256"]:
            raise ValueError("Signing store does not match the pinned public certificate")
    except Exception:
        if created and path is not None:
            path.unlink(missing_ok=True)
        # Errors from keytool/base64 must not expose a credential or key payload.
        print("Stable signing preparation failed. Check runner context and both repository secrets; no fallback signing is permitted.", file=sys.stderr)
        return 1
    print("Verified stable development signing identity; credentials are not printed or retained in artifacts.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
