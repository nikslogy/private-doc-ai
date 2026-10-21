#!/usr/bin/env python3
"""Synthetic standard ZIP/ELF parser fixtures, never real dependency evidence."""

from __future__ import annotations

import importlib.util
import io
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

SCRIPT = Path(__file__).with_name("check-native-libraries.py")
SPEC = importlib.util.spec_from_file_location("native_check", SCRIPT)
native = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = native
SPEC.loader.exec_module(native)


def elf(abi="arm64-v8a", alignment=16384, *, address=0, offset=0, loads=1):
    """Small standard ET_DYN, with PT_LOAD bytes covering its own ELF header."""
    elf_class, machine = native.ABIS[abi]
    identifier = b"\x7fELF" + bytes((elf_class, 1, 1)) + bytes(9)
    header_size, program_size = (64, 56) if elf_class == 2 else (52, 32)
    length = header_size + program_size * max(1, loads)
    if elf_class == 2:
        header = struct.pack("<16sHHIQQQIHHHHHH", identifier, 3, machine, 1, 0,
                             header_size, 0, 0, header_size, program_size, max(1, loads), 0, 0, 0)
        segment = struct.pack("<IIQQQQQQ", 1 if loads else 0, 5, offset, address, 0,
                              length - offset, length, alignment)
    else:
        header = struct.pack("<16sHHIIIIIHHHHHH", identifier, 3, machine, 1, 0,
                             header_size, 0, 0, header_size, program_size, max(1, loads), 0, 0, 0)
        segment = struct.pack("<IIIIIIII", 1 if loads else 0, offset, address, 0,
                              length - offset, length, 5, alignment)
    return header + segment * max(1, loads)


def archive_bytes(members, *, aligned=True, compression=zipfile.ZIP_STORED):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=compression) as archive:
        for name, payload in members:
            info = zipfile.ZipInfo(name)
            info.compress_type = compression
            if aligned:
                padding = -(stream.tell() + 30 + len(name.encode("utf-8"))) % native.PAGE_SIZE
                if padding < 4:
                    padding += native.PAGE_SIZE
                info.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
            archive.writestr(info, payload)
    return stream.getvalue()


class NativeCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "synthetic.apk"

    def inspect(self, payload):
        self.path.write_bytes(payload)
        return native.inspect_apk(self.path)

    def rejected(self, payload, message):
        with self.assertRaisesRegex(native.VerificationError, message):
            self.inspect(payload)

    def test_aligned_64_bit_abi_inventory_and_content_identities(self):
        members = [(f"lib/{abi}/libsynthetic.so", elf(abi)) for abi in ("arm64-v8a", "x86_64")]
        libraries = self.inspect(archive_bytes(members))
        self.assertEqual([item.abi for item in libraries], ["arm64-v8a", "x86_64"])
        self.assertTrue(all(item.payload_offset % 16384 == 0 for item in libraries))
        self.assertTrue(all(item.minimum_alignment == 16384 for item in libraries))
        self.assertNotEqual(libraries[0].sha256, libraries[1].sha256)

    def test_4k_elf_fails_even_when_zip_is_16k_aligned(self):
        self.rejected(archive_bytes([("lib/arm64-v8a/libbad.so", elf(alignment=4096))]), "PT_LOAD.*16 KiB")

    def test_4k_elf_in_compressed_zip_still_fails(self):
        self.rejected(archive_bytes([("lib/x86_64/libbad.so", elf("x86_64", alignment=4096))],
                                    compression=zipfile.ZIP_DEFLATED), "PT_LOAD.*16 KiB")

    def test_uncompressed_payload_requires_local_16k_zip_alignment(self):
        self.rejected(archive_bytes([("lib/arm64-v8a/libbad.so", elf())], aligned=False), "ZIP payload.*16 KiB")

    def test_compressed_payload_does_not_require_mmap_zip_alignment(self):
        libraries = self.inspect(archive_bytes([("lib/x86_64/libsynthetic.so", elf("x86_64"))],
                                              aligned=False, compression=zipfile.ZIP_DEFLATED))
        self.assertTrue(libraries[0].compressed)

    def test_32_bit_libraries_are_inventoried_without_64_bit_claim(self):
        members = [(f"lib/{abi}/libsynthetic.so", elf(abi, alignment=4096)) for abi in ("armeabi-v7a", "x86")]
        libraries = self.inspect(archive_bytes(members, aligned=False))
        self.assertEqual({item.abi for item in libraries}, {"armeabi-v7a", "x86"})
        result = subprocess.run([sys.executable, str(SCRIPT), str(self.path)], text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.count("16KiB=unverified(32-bit)"), 2)

    def test_packaged_abi_must_match_elf_machine(self):
        self.rejected(archive_bytes([("lib/arm64-v8a/libwrong.so", elf("x86_64"))]), "machine.*ABI")

    def test_packaged_abi_must_match_elf_class(self):
        self.rejected(archive_bytes([("lib/arm64-v8a/libwrong.so", elf("x86"))]), "class.*ABI")

    def test_native_layout_and_unknown_abi_fail(self):
        for name in ("lib/mips/libbad.so", "assets/libbad.so", "lib/arm64-v8a/nested/libbad.so"):
            with self.subTest(name=name):
                self.rejected(archive_bytes([(name, elf())]), "supported lib/ABI")

    def test_corrupt_and_truncated_elf_fail(self):
        for payload in (b"not elf", b"\x7fELF", elf()[:20], elf()[:-1]):
            with self.subTest(length=len(payload)):
                self.rejected(archive_bytes([("lib/arm64-v8a/libbad.so", payload)]), "ELF")

    def test_non_shared_or_big_endian_elf_fail(self):
        for position, replacement in ((16, b"\x02\x00"), (5, b"\x02")):
            payload = bytearray(elf())
            payload[position:position + len(replacement)] = replacement
            self.rejected(archive_bytes([("lib/arm64-v8a/libbad.so", payload)]), "ELF")

    def test_malformed_program_table_and_load_segment_fail(self):
        cases = []
        for position, value in ((54, 0), (56, 0), (56, 65535)):
            payload = bytearray(elf())
            struct.pack_into("<H", payload, position, value)
            cases.append(payload)
        payload = bytearray(elf())
        struct.pack_into("<Q", payload, 32, len(payload) + 1)
        cases.append(payload)
        payload = bytearray(elf())
        struct.pack_into("<Q", payload, 64 + 32, len(payload) + 1)
        cases.append(payload)
        payload = bytearray(elf())
        struct.pack_into("<Q", payload, 64 + 40, 1)
        cases.append(payload)
        for payload in cases:
            with self.subTest(payload=bytes(payload[:64]).hex()):
                self.rejected(archive_bytes([("lib/arm64-v8a/libbad.so", payload)]), "ELF")

    def test_all_load_segments_are_checked(self):
        payload = bytearray(elf(loads=2))
        struct.pack_into("<Q", payload, 64 + 56 + 48, 4096)
        self.rejected(archive_bytes([("lib/arm64-v8a/libbad.so", payload)]), "PT_LOAD.*16 KiB")

    def test_incongruent_non_power_and_absent_load_segments_fail(self):
        for payload, reason in ((elf(address=1), "incongruent"),
                                (elf(alignment=20000), "power of two"),
                                (elf(loads=0), "no PT_LOAD")):
            with self.subTest(reason=reason):
                self.rejected(archive_bytes([("lib/arm64-v8a/libbad.so", payload)]), reason)

    def test_unsafe_paths_and_control_characters_fail_without_echoing_them(self):
        for name in ("../private-name", "/absolute", "a/../b", "a//b", "a/./b", "a\\b", "C:/x", "a\nb"):
            with self.subTest(name=name):
                self.rejected(archive_bytes([(name, b"synthetic")]), "Unsafe ZIP member path")

    def test_symbolic_links_and_duplicate_names_fail(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            info = zipfile.ZipInfo("link")
            info.external_attr = (0o120777 << 16)
            archive.writestr(info, "synthetic")
        self.rejected(stream.getvalue(), "symbolic links")
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            duplicate = archive_bytes([("same", b"a"), ("same", b"b")])
        self.rejected(duplicate, "Duplicate")

    def test_corrupt_truncated_and_trailing_zip_fail(self):
        good = archive_bytes([("empty", b"")])
        for payload in (b"not a zip", good[:-1], good + b"trailing"):
            with self.subTest(length=len(payload)):
                self.rejected(payload, "ZIP")

    def test_zip_bomb_metadata_is_rejected_before_native_read(self):
        self.rejected(archive_bytes([("assets/synthetic.txt", bytes(1024**2))],
                                    compression=zipfile.ZIP_DEFLATED), "expansion ratio")
        payload = bytearray(archive_bytes([("assets/synthetic.txt", b"small")]))
        central = payload.index(b"PK\x01\x02")
        struct.pack_into("<L", payload, central + 24, native.MAX_ENTRY_BYTES + 1)
        self.rejected(payload, "expanded-size bounds")

    def test_entry_count_and_directory_size_bounds(self):
        good = archive_bytes([("empty", b"")])
        for offset, format_, value in ((10, "<H", native.MAX_ENTRIES + 1),
                                       (12, "<L", native.MAX_DIRECTORY_BYTES + 1)):
            payload = bytearray(good)
            end = payload.rfind(b"PK\x05\x06")
            struct.pack_into(format_, payload, end + offset, value)
            if offset == 10:
                struct.pack_into("<H", payload, end + 8, value)
            self.rejected(payload, "central directory.*limits")

    def test_native_crc_and_local_central_disagreement_fail(self):
        good = archive_bytes([("lib/arm64-v8a/libsynthetic.so", elf())])
        payload = bytearray(good)
        payload[native.PAGE_SIZE + 20] ^= 1
        self.rejected(payload, "malformed APK")
        payload = bytearray(good)
        payload[30] ^= 1
        self.rejected(payload, "filenames disagree")

    def test_central_lengths_cannot_fake_local_zip_alignment(self):
        payload = bytearray(archive_bytes([("lib/arm64-v8a/libsynthetic.so", elf())]))
        struct.pack_into("<H", payload, 28, 0)  # local extra length, central still aligned
        self.rejected(payload, "ZIP payload.*16 KiB")

    def test_no_native_library_is_explicitly_not_runtime_evidence(self):
        self.inspect(archive_bytes([("AndroidManifest.xml", b"synthetic manifest marker")]))
        result = subprocess.run([sys.executable, str(SCRIPT), str(self.path)], text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("no packaged .so libraries; no native compatibility claim", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
