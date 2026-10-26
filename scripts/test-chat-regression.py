#!/usr/bin/env python3
"""Synthetic report rejection tests; no Android, native chat or UI result implied."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET


SOURCE = Path(__file__).resolve().with_name("verify-chat-regression.py")
SPEC = importlib.util.spec_from_file_location("chat_regression_gate", SOURCE)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class ChatRegressionGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.previous_root = GATE.ROOT
        GATE.ROOT = Path(self.temporary.name)
        self.addCleanup(setattr, GATE, "ROOT", self.previous_root)
        self.directory = GATE.result_roots("offlineDebug")[0]
        self.directory.mkdir(parents=True)

    def report(self, names=None, **counts):
        names = sorted(GATE.EXPECTED_TESTS) if names is None else names
        suite = ET.Element("testsuite", tests=str(len(names)), failures="0", errors="0", skipped="0")
        suite.attrib.update({key: str(value) for key, value in counts.items()})
        for name in names:
            ET.SubElement(suite, "testcase", name=name, classname=GATE.EXPECTED_CLASS)
        path = self.directory / "TEST-synthetic.xml"
        ET.ElementTree(suite).write(path, encoding="utf-8")
        return path, suite

    def write(self, path, suite):
        ET.ElementTree(suite).write(path, encoding="utf-8")

    def test_complete_named_cases_are_required(self):
        self.report()
        self.assertEqual(len(GATE.EXPECTED_TESTS), GATE.verify(self.directory))

    def test_no_report_cannot_pass(self):
        with self.assertRaises(ValueError):
            GATE.verify(self.directory)

    def test_one_missing_lifecycle_case_cannot_pass(self):
        self.report(sorted(GATE.EXPECTED_TESTS)[1:])
        with self.assertRaises(ValueError):
            GATE.verify(self.directory)

    def test_previous_nine_case_suite_cannot_replace_answering_regressions(self):
        self.report([
            "selectedDocumentQuestionReturnsExactCitedPremium",
            "missingConflictingAndPartialFactsNeverInventAUniqueValue",
            "clearConversationRevokesInFlightSourceAndClearsPrivateDraft",
            "sourceChangeRejectsLateCompletionFromPreviousDocument",
            "lockRejectsOldCompletionInNewUnlockedSession",
            "unselectedPrivateQuestionsAndActionRequestsUseNoticesWithoutNativeModel",
            "reindexInvalidationClearsCitationsAndRevokesSourceRead",
            "mismatchedSourceRevisionCannotPublishCitations",
            "removedSourceClearsChatAndSelection",
        ])
        with self.assertRaises(ValueError):
            GATE.verify(self.directory)

    def test_duplicate_case_cannot_replace_missing_execution(self):
        names = sorted(GATE.EXPECTED_TESTS)
        self.report(names + [names[0]])
        with self.assertRaises(ValueError):
            GATE.verify(self.directory)

    def test_unexpected_test_class_cannot_pass(self):
        path, suite = self.report()
        suite.find("testcase").set("classname", "synthetic.Unexpected")
        self.write(path, suite)
        with self.assertRaises(ValueError):
            GATE.verify(self.directory)

    def test_unknown_case_identity_cannot_pass(self):
        path, suite = self.report()
        suite.find("testcase").set("name", "unrecognizedCase")
        self.write(path, suite)
        with self.assertRaises(ValueError):
            GATE.verify(self.directory)

    def test_suite_failed_skipped_or_disabled_counts_cannot_pass(self):
        for field in ("failures", "errors", "skipped", "disabled"):
            with self.subTest(field=field):
                self.report(**{field: 1})
                with self.assertRaises(ValueError):
                    GATE.verify(self.directory)
            with self.subTest(aggregate=field):
                path, suite = self.report()
                root = ET.Element("testsuites", **{field: "1"})
                root.append(suite)
                self.write(path, root)
                with self.assertRaises(ValueError):
                    GATE.verify(self.directory)

    def test_per_case_failure_or_skip_cannot_hide_behind_zero_suite_counts(self):
        for tag in ("failure", "error", "skipped"):
            with self.subTest(tag=tag):
                path, suite = self.report()
                ET.SubElement(suite.find("testcase"), tag)
                self.write(path, suite)
                with self.assertRaises(ValueError):
                    GATE.verify(self.directory)

    def test_suite_test_count_cannot_disagree_with_cases(self):
        self.report(tests=len(GATE.EXPECTED_TESTS) + 1)
        with self.assertRaises(ValueError):
            GATE.verify(self.directory)

    def test_stale_second_agp_result_root_is_ambiguous(self):
        self.report()
        other = GATE.result_roots("offlineDebug")[1]
        other.mkdir(parents=True)
        (other / "TEST-stale.xml").write_text("<testsuite/>")
        with self.assertRaises(ValueError):
            GATE.select_result_root("offlineDebug")

    def test_symlinked_result_ancestor_is_rejected(self):
        parent = GATE.ROOT / "app/build/outputs/androidTest-results/connected/debug"
        parent.parent.mkdir(parents=True, exist_ok=True)
        target = GATE.ROOT / "synthetic-symlink-target"
        target.mkdir()
        parent.symlink_to(target, target_is_directory=True)
        with self.assertRaises(ValueError):
            GATE.require_safe_result_path(GATE.result_roots("connectedDebug")[1])

    def test_missing_variant_has_no_selected_root(self):
        self.report()
        with self.assertRaises(ValueError):
            GATE.select_result_root("connectedDebug")


if __name__ == "__main__":
    unittest.main()
