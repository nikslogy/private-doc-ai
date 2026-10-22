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
    "com.privateassistant.platform.documents.OcrBoundaryInstrumentedTest": 3,
    "com.privateassistant.platform.documents.TesseractOcrInstrumentedTest": 6,
    "com.privateassistant.platform.inference.InstalledModelStoreInstrumentedTest": 10,
    "com.privateassistant.platform.inference.ModelImportPrivacyInstrumentedTest": 1,
}


def result_roots(variant: str) -> tuple[Path, Path]:
    base = ROOT / "platform/build/outputs/androidTest-results/connected"
    flavor = {"offlineDebug": "offline", "connectedDebug": "connected"}[variant]
    return base / variant, base / "debug/flavors" / flavor


def select_result_root(variant: str) -> Path:
    populated = []
    for directory in result_roots(variant):
        if directory.is_symlink():
            raise ValueError("Android regression result directory must not be a symlink")
        if any(directory.rglob("TEST-*.xml")):
            populated.append(directory)
    if len(populated) != 1:
        raise ValueError("Missing or ambiguous Android regression results for the required variant")
    return populated[0]


def verify(directory: Path) -> int:
    files = sorted(directory.rglob("TEST-*.xml"))
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


def diagnose() -> None:
    """Print complete failures from this synthetic-only test subset without declaring a pass."""
    for variant in ("offlineDebug", "connectedDebug"):
        print(f"Android regression diagnostics: {variant}", flush=True)
        found = False
        for directory in result_roots(variant):
            if directory.is_symlink():
                print("Result directory symlink rejected", flush=True)
                continue
            for path in sorted(directory.rglob("TEST-*.xml")):
                found = True
                if path.is_symlink() or path.stat().st_size > 8 * 1024 * 1024:
                    print("Unexpected regression report file rejected", flush=True)
                    continue
                print(f"Report: {path.relative_to(ROOT)}", flush=True)
                try:
                    root = ET.parse(path).getroot()
                except ET.ParseError:
                    print("Malformed XML report", flush=True)
                    continue
                for suite in root.iter("testsuite"):
                    print("Suite counts: " + ", ".join(
                        f"{name}={suite.get(name, '0')}"
                        for name in ("tests", "failures", "errors", "skipped", "disabled")
                    ), flush=True)
                for case in root.iter("testcase"):
                    for tag in ("failure", "error", "skipped"):
                        for failure in case.findall(tag):
                            print(f"{case.get('classname', '')}#{case.get('name', '')}: {tag}", flush=True)
                            if failure.get("message"):
                                print(failure.get("message"), flush=True)
                            print("".join(failure.itertext()), flush=True)
        if not found:
            print("No XML report produced for this variant", flush=True)


def main() -> int:
    if sys.argv[1:] == ["--diagnose"]:
        try:
            diagnose()
        except (OSError, ValueError):
            print("Could not read Android regression diagnostics", file=sys.stderr)
        return 1  # Diagnostics never satisfy the verification gate.
    if len(sys.argv) != 1:
        print("Usage: python3 scripts/verify-unlock-regression.py [--diagnose]", file=sys.stderr)
        return 2
    try:
        for variant in ("offlineDebug", "connectedDebug"):
            count = verify(select_result_root(variant))
            print(f"{variant}: {count} verified non-skipped Android cases", flush=True)
    except (ValueError, OSError, ET.ParseError) as failure:
        print(f"Vault regression result verification FAILED: {failure}", file=sys.stderr)
        return 1
    print("This regression subset does not establish biometric/device-credential or full device release readiness.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
