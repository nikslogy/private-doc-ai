#!/usr/bin/env python3
"""Require fresh, complete synthetic app chat-controller instrumentation results."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_CLASS = "com.privateassistant.app.AssistantChatLifecycleTest"
EXPECTED_TESTS = {
    "selectedDocumentQuestionReturnsExactCitedPremium",
    "missingConflictingAndPartialFactsNeverInventAUniqueValue",
    "clearConversationRevokesInFlightSourceAndClearsPrivateDraft",
    "sourceChangeRejectsLateCompletionFromPreviousDocument",
    "lockRejectsOldCompletionInNewUnlockedSession",
    "unselectedPrivateQuestionsAndActionRequestsUseNoticesWithoutNativeModel",
    "reindexInvalidationClearsCitationsAndRevokesSourceRead",
    "mismatchedSourceRevisionCannotPublishCitations",
    "removedSourceClearsChatAndSelection",
}
MAX_REPORT_BYTES = 8 * 1024 * 1024


def result_roots(variant: str) -> tuple[Path, Path]:
    base = ROOT / "app/build/outputs/androidTest-results/connected"
    flavor = {"offlineDebug": "offline", "connectedDebug": "connected"}[variant]
    return base / variant, base / "debug/flavors" / flavor


def require_safe_result_path(path: Path) -> None:
    try:
        relative = path.relative_to(ROOT)
    except ValueError as error:
        raise ValueError("Chat result paths must stay inside the project") from error
    current = ROOT
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError("Chat result paths must not contain symbolic links")
    if not path.resolve().is_relative_to(ROOT.resolve()):
        raise ValueError("Chat result paths must stay inside the project")


def select_result_root(variant: str) -> Path:
    populated = []
    for directory in result_roots(variant):
        require_safe_result_path(directory)
        if any(directory.rglob("TEST-*.xml")):
            populated.append(directory)
    if len(populated) != 1:
        raise ValueError("Missing or ambiguous fresh app chat regression results")
    return populated[0]


def verify(directory: Path) -> int:
    require_safe_result_path(directory)
    files = sorted(directory.rglob("TEST-*.xml"))
    if not files:
        raise ValueError("No app chat regression XML results were produced")
    observed = set()
    for path in files:
        require_safe_result_path(path)
        if not path.is_file() or path.stat().st_size > MAX_REPORT_BYTES:
            raise ValueError("Unexpected chat regression report file")
        root = ET.parse(path).getroot()
        if root.tag not in {"testsuite", "testsuites"}:
            raise ValueError("Unexpected chat regression XML root")
        if not list(root.iter("testsuite")):
            raise ValueError("The chat report contains no test suite")
        for suite in root.iter():
            if suite.tag not in {"testsuite", "testsuites"}:
                continue
            for field in ("failures", "errors", "skipped", "disabled"):
                if int(suite.get(field, "0")) != 0:
                    raise ValueError("Chat regression suite reports failed or incomplete execution")
            if "tests" in suite.attrib and int(suite.get("tests")) != len(list(suite.iter("testcase"))):
                raise ValueError("Chat regression suite count disagrees with its cases")
        for case in root.iter("testcase"):
            if case.get("classname") != EXPECTED_CLASS:
                raise ValueError("Unexpected app chat regression test class")
            name = case.get("name", "")
            if name not in EXPECTED_TESTS or name in observed:
                raise ValueError("Unexpected, missing or duplicate chat test identity")
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                raise ValueError("An app chat regression case failed or was skipped")
            observed.add(name)
    if observed != EXPECTED_TESTS:
        raise ValueError("The required chat lifecycle cases did not all execute")
    count = len(observed)
    print(f"{directory.name}: PASS, {count} app chat controller cases, none skipped")
    return count


def diagnose() -> None:
    """Read failures from this synthetic-only class without granting a pass."""
    for variant in ("offlineDebug", "connectedDebug"):
        print(f"Chat regression diagnostics: {variant}", flush=True)
        found = False
        for directory in result_roots(variant):
            require_safe_result_path(directory)
            for path in sorted(directory.rglob("TEST-*.xml")):
                found = True
                require_safe_result_path(path)
                if not path.is_file() or path.stat().st_size > MAX_REPORT_BYTES:
                    print("Unexpected report file rejected", flush=True)
                    continue
                print(f"Report: {path.relative_to(ROOT)}", flush=True)
                root = ET.parse(path).getroot()
                for case in root.iter("testcase"):
                    if case.get("classname") != EXPECTED_CLASS:
                        print("Unexpected test class rejected", flush=True)
                        continue
                    for tag in ("failure", "error", "skipped"):
                        for failure in case.findall(tag):
                            print(f"{EXPECTED_CLASS}#{case.get('name', '')}: {tag}", flush=True)
                            if failure.get("message"):
                                print(failure.get("message"), flush=True)
                            print("".join(failure.itertext()), flush=True)
        if not found:
            print("No XML report produced for this variant", flush=True)


def main() -> int:
    if sys.argv[1:] == ["--diagnose"]:
        try:
            diagnose()
        except (OSError, ValueError, ET.ParseError):
            print("Could not read app chat regression diagnostics", file=sys.stderr)
        return 1  # Diagnostics never satisfy this gate.
    if len(sys.argv) != 1:
        print("Usage: python3 scripts/verify-chat-regression.py [--diagnose]", file=sys.stderr)
        return 2
    try:
        for variant in ("offlineDebug", "connectedDebug"):
            count = verify(select_result_root(variant))
            print(f"{variant}: {count} verified non-skipped app chat cases", flush=True)
    except (OSError, ValueError, ET.ParseError) as failure:
        print(f"App chat regression result verification FAILED: {failure}", file=sys.stderr)
        return 1
    print("Controller tests do not establish Compose tap behavior, native chat quality or physical-device release readiness.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
