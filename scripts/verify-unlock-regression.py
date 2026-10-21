#!/usr/bin/env python3
"""Require complete, non-skipped Android results for the bounded vault regression suite."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "com.privateassistant.platform.vault.SqlCipherInstrumentedTest": 3,
    "com.privateassistant.platform.vault.VaultEvidenceMigrationInstrumentedTest": 3,
    "com.privateassistant.platform.vault.StreamingCryptoInstrumentedTest": 4,
    "com.privateassistant.platform.vault.DurableFilesInstrumentedTest": 2,
}


def verify(directory: Path) -> int:
    files = sorted(directory.rglob("*.xml"))
    if not files:
        raise ValueError("No Android regression XML results were produced")
    observed: dict[str, set[str]] = {name: set() for name in EXPECTED}
    for path in files:
        if path.is_symlink() or path.stat().st_size > 8 * 1024 * 1024:
            raise ValueError("Unexpected regression report file")
        root = ET.parse(path).getroot()
        for suite in root.iter("testsuite"):
            for field in ("failures", "errors", "skipped", "disabled"):
                if int(suite.get(field, "0")) != 0:
                    raise ValueError("Android regression suite reports incomplete or failed execution")
        for case in root.iter("testcase"):
            name = case.get("classname", "")
            if name not in EXPECTED:
                raise ValueError("An unexpected Android test class was executed")
            test = case.get("name", "")
            if not test or test in observed[name]:
                raise ValueError("Missing or duplicate Android test identity")
            if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
                raise ValueError("An Android regression case failed or was skipped")
            observed[name].add(test)
    for name, minimum in EXPECTED.items():
        if len(observed[name]) < minimum:
            raise ValueError("Required Android regression cases did not all execute")
    count = sum(map(len, observed.values()))
    print(f"{directory.name}: PASS, {count} Android regression cases, none skipped")
    return count


def main() -> int:
    if len(sys.argv) != 1:
        print("Usage: python3 scripts/verify-unlock-regression.py", file=sys.stderr)
        return 2
    try:
        for variant in ("offlineDebug", "connectedDebug"):
            verify(ROOT / "platform/build/outputs/androidTest-results/connected" / variant)
    except (ValueError, OSError, ET.ParseError) as failure:
        print(f"Vault regression result verification FAILED: {failure}", file=sys.stderr)
        return 1
    print("This regression subset does not establish biometric/device-credential or full device release readiness.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
