#!/usr/bin/env python3
"""Validate fixture integrity and deterministic regeneration, without dependencies."""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import tempfile
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "test-fixtures" / "generated"
spec = importlib.util.spec_from_file_location("generator", ROOT / "scripts" / "generate-fixtures.py")
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class FixtureIntegrityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((FIXTURES / "fixtures.json").read_text())
        cls.documents = {doc["id"]: doc for doc in cls.manifest["documents"]}

    def test_bytes_are_reproducible(self):
        with tempfile.TemporaryDirectory() as folder:
            regenerated = Path(folder)
            generator.build(regenerated)
            self.assertEqual(sorted(p.name for p in regenerated.iterdir()), sorted(p.name for p in FIXTURES.iterdir()))
            for path in regenerated.iterdir():
                with self.subTest(file=path.name):
                    self.assertEqual(path.read_bytes(), (FIXTURES / path.name).read_bytes())

    def test_digests_and_labels(self):
        self.assertIs(self.manifest["synthetic"], True)
        self.assertEqual(len(self.documents), 6)
        for doc in self.documents.values():
            with self.subTest(document=doc["id"]):
                data = (FIXTURES / doc["file"]).read_bytes()
                self.assertEqual(doc["bytes"], len(data))
                self.assertEqual(doc["sha256"], hashlib.sha256(data).hexdigest())
                self.assertNotIn("..", doc["file"])
                for number, page in enumerate(doc["expected_pages"], 1):
                    self.assertEqual(page["page_number"], number)

    def test_pdf_has_two_text_pages_and_valid_xref_offsets(self):
        data = (FIXTURES / "northstar-policy.pdf").read_bytes()
        self.assertEqual(len(re.findall(rb"/Type /Page\b", data)), 2)
        self.assertIn(b"/Count 2", data)
        self.assertIn(b"Policy number: 00004217", data)
        self.assertIn(b"Total premium: INR 11800.00", data)
        xref_offset = int(re.search(rb"startxref\n(\d+)\n", data).group(1))
        self.assertEqual(data[xref_offset:xref_offset + 4], b"xref")
        xref = data[xref_offset:].splitlines()
        object_count = int(xref[1].split()[1])
        for object_id in range(1, object_count):
            offset = int(xref[2 + object_id].split()[0])
            self.assertTrue(data[offset:].startswith(f"{object_id} 0 obj\n".encode()))

    def test_scan_png_is_decodable_and_contains_only_raster_data(self):
        data = (FIXTURES / "policy-scan.png").read_bytes()
        self.assertTrue(data.startswith(b"\x89PNG\r\n\x1a\n"))
        offset, image_data, seen = 8, bytearray(), []
        while offset < len(data):
            size = struct.unpack_from(">I", data, offset)[0]
            kind = data[offset + 4:offset + 8]
            payload = data[offset + 8:offset + 8 + size]
            crc = struct.unpack_from(">I", data, offset + 8 + size)[0]
            self.assertEqual(crc, zlib.crc32(kind + payload))
            seen.append(kind)
            if kind == b"IHDR":
                width, height, depth, color, _, _, _ = struct.unpack(">IIBBBBB", payload)
                self.assertEqual((depth, color), (8, 0))
            if kind == b"IDAT":
                image_data.extend(payload)
            offset += size + 12
        pixels = zlib.decompress(bytes(image_data))
        self.assertEqual(len(pixels), (width + 1) * height)
        self.assertEqual(seen, [b"IHDR", b"IDAT", b"IEND"])
        self.assertIn(0, pixels)
        self.assertIn(255, pixels)
        self.assertEqual(offset, len(data))

    def test_ground_truth_quotes_match_selected_pages_and_utf16_offsets(self):
        truth = json.loads((FIXTURES / "ground-truth.json").read_text())
        self.assertIs(truth["not_a_benchmark"], True)
        self.assertEqual(len(truth["cases"]), 7)
        ids = [case["id"] for case in truth["cases"]]
        self.assertEqual(len(ids), len(set(ids)))
        for case in truth["cases"]:
            with self.subTest(case=case["id"]):
                for evidence in case["evidence"]:
                    self.assertIn(evidence["document_id"], case["selected_documents"])
                    doc = self.documents[evidence["document_id"]]
                    page = doc["expected_pages"][evidence["page_number"] - 1]["text"]
                    encoded = page.encode("utf-16-le")
                    quote = encoded[evidence["start_utf16"] * 2:evidence["end_utf16"] * 2].decode("utf-16-le")
                    self.assertEqual(evidence["quote"], quote)
                if case["expected_disposition"] == "insufficient-evidence":
                    self.assertEqual(case["evidence"], [])
                    self.assertIsNone(case["answer_contains"])
                else:
                    self.assertTrue(case["evidence"])

    def test_corrupt_fixture_is_truncated_and_injection_is_explicit(self):
        data = (FIXTURES / "corrupt.pdf").read_bytes()
        self.assertNotIn(b"%%EOF", data)
        self.assertNotIn(b"xref", data)
        self.assertIn("example.invalid", (FIXTURES / "prompt-injection.txt").read_text())
        self.assertEqual(self.documents["corrupt-pdf"]["expected_pages"], [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
