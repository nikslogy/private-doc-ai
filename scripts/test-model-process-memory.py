#!/usr/bin/env python3
"""Synthetic parsing checks for the opt-in CI model memory sampler."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "sample_model_process_memory", Path(__file__).with_name("sample-model-process-memory.py")
)
SAMPLER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SAMPLER)


class ProcessMemoryStatusTests(unittest.TestCase):
    def test_converts_only_reviewed_numeric_fields_to_bytes(self):
        self.assertEqual(
            SAMPLER.parse_process_memory_status("VmRSS:\t1234 kB\nVmHWM: 2345 kB\n"),
            {"rssBytes": 1234 * 1024, "hwmBytes": 2345 * 1024},
        )

    def test_ignores_other_process_status_fields(self):
        self.assertEqual(
            SAMPLER.parse_process_memory_status(
                "Name:\tSYNTHETIC_PRIVATE_LABEL\nUid: 1234\nVmPeak: 9876 kB\n"
                "VmRSS: 3 kB\nVmHWM: 4 kB\n"
            ),
            {"rssBytes": 3072, "hwmBytes": 4096},
        )

    def test_zero_is_a_numeric_memory_value(self):
        self.assertEqual(
            SAMPLER.parse_process_memory_status("VmRSS: 0 kB\nVmHWM: 0 kB\n"),
            {"rssBytes": 0, "hwmBytes": 0},
        )

    def test_requires_both_fields(self):
        for value in ("", "VmRSS: 4 kB\n", "VmHWM: 4 kB\n"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                SAMPLER.parse_process_memory_status(value)

    def test_rejects_duplicate_reviewed_fields(self):
        for field in ("VmRSS", "VmHWM"):
            with self.subTest(field=field), self.assertRaises(ValueError):
                SAMPLER.parse_process_memory_status(
                    "VmRSS: 4 kB\nVmHWM: 5 kB\n" + field + ": 4 kB\n"
                )

    def test_rejects_nondecimal_negative_fractional_and_wrong_unit_values(self):
        for value in ("-1 kB", "+1 kB", "1.5 kB", "abc kB", "1 MB", "1 kB extra"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                SAMPLER.parse_process_memory_status("VmRSS: " + value + "\nVmHWM: 4 kB\n")

    def test_rejects_overflow_after_kib_conversion(self):
        too_large = (2**63 - 1) // 1024 + 1
        for field in ("VmRSS", "VmHWM"):
            status = {"VmRSS": 1, "VmHWM": 1, field: too_large}
            with self.subTest(field=field), self.assertRaises(ValueError):
                SAMPLER.parse_process_memory_status(
                    "\n".join(f"{key}: {value} kB" for key, value in status.items())
                )

    def test_accepts_largest_whole_kib_value_in_signed_64_bit_bytes(self):
        maximum_kib = (2**63 - 1) // 1024
        self.assertEqual(
            SAMPLER.parse_process_memory_status(
                f"VmRSS: {maximum_kib} kB\nVmHWM: {maximum_kib} kB\n"
            ),
            {"rssBytes": maximum_kib * 1024, "hwmBytes": maximum_kib * 1024},
        )


class UniqueTestPidTests(unittest.TestCase):
    def test_extracts_exact_positive_pid_metadata(self):
        self.assertEqual(SAMPLER.unique_test_pid("INSTRUMENTATION_STATUS: modelProbePid=2985\n"), 2985)

    def test_repeated_same_pid_is_one_identity(self):
        metadata = "INSTRUMENTATION_STATUS: modelProbePid=2985\n"
        self.assertEqual(SAMPLER.unique_test_pid(metadata * 3), 2985)

    def test_no_metadata_returns_none(self):
        self.assertIsNone(SAMPLER.unique_test_pid("INSTRUMENTATION_STATUS_CODE: 1\n"))

    def test_unrelated_or_embedded_text_cannot_supply_pid(self):
        self.assertIsNone(
            SAMPLER.unique_test_pid(
                "prefix INSTRUMENTATION_STATUS: modelProbePid=2985\n"
                "INSTRUMENTATION_STATUS: anotherField=2985\n"
            )
        )

    def test_rejects_distinct_test_pids(self):
        with self.assertRaises(ValueError):
            SAMPLER.unique_test_pid(
                "INSTRUMENTATION_STATUS: modelProbePid=2985\n"
                "INSTRUMENTATION_STATUS: modelProbePid=2986\n"
            )

    def test_rejects_invalid_pid_metadata(self):
        for value in ("", "0", "-1", "+1", "abc", "1.5", "2147483648", "2985 trailing"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                SAMPLER.unique_test_pid("INSTRUMENTATION_STATUS: modelProbePid=" + value + "\n")

    def test_accepts_maximum_supported_pid(self):
        self.assertEqual(
            SAMPLER.unique_test_pid("INSTRUMENTATION_STATUS: modelProbePid=2147483647\n"),
            2147483647,
        )


class MemoryReportTests(unittest.TestCase):
    def test_nine_hundred_numeric_samples_fit_within_report_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "memory.json"
            samples = [[index * 1000, 2**63 - 1, 2**63 - 1] for index in range(900)]
            report = {"schemaVersion": 1, "sampleCount": len(samples), "samples": samples}
            SAMPLER.write_report(output, report)
            self.assertLessEqual(output.stat().st_size, 120_000)
            self.assertEqual(json.loads(output.read_text()), report)
            self.assertEqual(list(Path(directory).iterdir()), [output])

    def test_oversized_report_does_not_replace_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "memory.json"
            output.write_text("existing synthetic report")
            with self.assertRaises(ValueError):
                SAMPLER.write_report(output, {"samples": "x" * 120_000})
            self.assertEqual(output.read_text(), "existing synthetic report")

    def test_output_symlink_is_rejected_without_writing_target(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "target.json"
            target.write_text("synthetic target preserved")
            output = Path(directory) / "memory.json"
            output.symlink_to(target)
            with self.assertRaises(ValueError):
                SAMPLER.write_report(output, {"sampleCount": 0})
            self.assertEqual(target.read_text(), "synthetic target preserved")

    def test_parent_directory_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            actual = Path(directory) / "actual"
            actual.mkdir()
            linked = Path(directory) / "linked"
            linked.symlink_to(actual, target_is_directory=True)
            with self.assertRaises(ValueError):
                SAMPLER.write_report(linked / "memory.json", {"sampleCount": 0})
            self.assertEqual(list(actual.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
