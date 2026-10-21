#!/usr/bin/env python3
"""Bounded, read-only APK native inventory and 64-bit 16 KiB static checks.

Only native dependency identities are reported; archive members are never
extracted. This is a packaging/ELF gate, not a device or licence assessment.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
from pathlib import Path
import re
import stat
import struct
import sys
import zipfile
import zlib


PAGE_SIZE = 16 * 1024
MAX_ARCHIVE_BYTES = 2 * 1024**3
MAX_DIRECTORY_BYTES = 16 * 1024**2
MAX_ENTRIES = 10_000
MAX_TOTAL_EXPANDED = 1024**3
MAX_ENTRY_BYTES = 256 * 1024**2
MAX_EXPANSION_RATIO = 200
MAX_PATH_BYTES = 1024
MAX_PROGRAM_HEADERS = 1024
ABIS = {"arm64-v8a": (2, 183), "x86_64": (2, 62),
        "armeabi-v7a": (1, 40), "x86": (1, 3)}
LIBRARY_NAME = re.compile(r"lib[A-Za-z0-9_.+\-]+\.so\Z")


class VerificationError(ValueError):
    """A safe fixed diagnostic; never includes arbitrary member contents."""


@dataclass(frozen=True)
class NativeLibrary:
    abi: str
    name: str
    size: int
    sha256: str
    load_segments: int
    minimum_alignment: int
    compressed: bool
    payload_offset: int


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise VerificationError(reason)


def zip_directory(source) -> tuple[int, int]:
    """Bound central-directory allocation before handing the file to zipfile."""
    source.seek(0, 2)
    size = source.tell()
    require(22 <= size <= MAX_ARCHIVE_BYTES, "APK size is outside bounded ZIP limits")
    source.seek(max(0, size - (22 + 65535)))
    tail = source.read(22 + 65535)
    position = tail.rfind(b"PK\x05\x06")
    require(position >= 0 and position + 22 <= len(tail), "Missing or truncated ZIP end record")
    fields = struct.unpack_from("<4s4H2LH", tail, position)
    _, disk, directory_disk, disk_entries, count, directory_size, directory_offset, comment_size = fields
    require(position + 22 + comment_size == len(tail), "Invalid ZIP end record or trailing bytes")
    require(disk == directory_disk == 0 and disk_entries == count, "Multi-disk ZIP is unsupported")
    require(count != 0xFFFF and directory_size != 0xFFFFFFFF and directory_offset != 0xFFFFFFFF,
            "ZIP64 APK is unsupported by this bounded gate")
    require(count <= MAX_ENTRIES and directory_size <= MAX_DIRECTORY_BYTES,
            "ZIP central directory exceeds bounded limits")
    end_position = size - len(tail) + position
    require(directory_offset + directory_size == end_position,
            "ZIP central-directory bounds are invalid")
    # ZipFile trusts directory bytes rather than the end-record entry count.
    # Count fixed headers before it allocates one ZipInfo object per member.
    source.seek(directory_offset)
    actual_count = 0
    while source.tell() < end_position:
        require(source.tell() + 46 <= end_position, "Truncated ZIP central-directory header")
        header = source.read(46)
        require(header[:4] == b"PK\x01\x02", "Invalid ZIP central-directory signature")
        name_size, extra_size, member_comment_size, member_disk = struct.unpack_from("<4H", header, 28)
        require(name_size <= MAX_PATH_BYTES and member_disk == 0, "ZIP central member exceeds bounds")
        next_header = source.tell() + name_size + extra_size + member_comment_size
        require(next_header <= end_position, "ZIP central member exceeds directory bounds")
        source.seek(next_header)
        actual_count += 1
        require(actual_count <= MAX_ENTRIES, "ZIP central directory exceeds bounded limits")
    require(actual_count == count, "ZIP central-directory entry count disagrees")
    return count, directory_offset


def safe_member(info: zipfile.ZipInfo) -> None:
    name = info.orig_filename
    parts = name.rstrip("/").split("/")
    require(name == info.filename and len(name.encode("utf-8")) <= MAX_PATH_BYTES
            and not name.startswith("/") and "\\" not in name and ":" not in name
            and not any(ord(character) < 32 or ord(character) == 127 for character in name)
            and all(part not in ("", ".", "..") for part in parts), "Unsafe ZIP member path")
    mode = info.external_attr >> 16
    require(not stat.S_ISLNK(mode), "ZIP symbolic links are unsupported")
    require(info.flag_bits & (1 | 64) == 0, "Encrypted ZIP members are unsupported")
    require(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
            "Unsupported ZIP compression method")
    require(0 <= info.file_size <= MAX_ENTRY_BYTES and 0 <= info.compress_size <= MAX_ARCHIVE_BYTES,
            "ZIP member exceeds expanded-size bounds")
    require(info.file_size < 1024**2 or info.file_size <= max(1, info.compress_size) * MAX_EXPANSION_RATIO,
            "ZIP member exceeds bounded expansion ratio")


def local_payload(source, info: zipfile.ZipInfo, directory_offset: int) -> tuple[int, int]:
    """Use local name/extra lengths, never central lengths, to find mmap bytes."""
    require(0 <= info.header_offset <= directory_offset - 30, "Invalid ZIP local-header bounds")
    source.seek(info.header_offset)
    header = source.read(30)
    require(len(header) == 30, "Truncated ZIP local header")
    signature, _, flags, method, _, _, crc, compressed, expanded, name_size, extra_size = struct.unpack("<4s5H3L2H", header)
    require(signature == b"PK\x03\x04", "Invalid ZIP local-header signature")
    require(flags == info.flag_bits and method == info.compress_type,
            "ZIP local/central compression flags disagree")
    require(name_size <= MAX_PATH_BYTES, "ZIP local filename exceeds bounds")
    raw_name = source.read(name_size)
    try:
        local_name = raw_name.decode("utf-8" if flags & 0x800 else "cp437")
    except UnicodeError as error:
        raise VerificationError("Invalid ZIP local filename encoding") from error
    require(local_name == info.orig_filename, "ZIP local/central filenames disagree")
    if not flags & 8:
        require((crc, compressed, expanded) == (info.CRC, info.compress_size, info.file_size),
                "ZIP local/central sizes or checksum disagree")
    offset = info.header_offset + 30 + name_size + extra_size
    end = offset + info.compress_size
    require(offset <= directory_offset and end <= directory_offset,
            "ZIP local payload exceeds archive bounds")
    return offset, end


def inspect_elf(payload: bytes, abi: str) -> tuple[int, int]:
    require(abi in ABIS, "Unsupported packaged native ABI")
    require(len(payload) >= 16 and payload[:4] == b"\x7fELF", "Missing or truncated ELF identification")
    elf_class, machine = ABIS[abi]
    require(payload[4] == elf_class, "ELF class does not match packaged ABI")
    require(payload[5] == 1 and payload[6] == 1, "ELF must use Android little-endian version 1 encoding")
    header_format = "<16sHHIQQQIHHHHHH" if elf_class == 2 else "<16sHHIIIIIHHHHHH"
    header_size = struct.calcsize(header_format)
    require(len(payload) >= header_size, "Truncated ELF header")
    fields = struct.unpack_from(header_format, payload)
    _, elf_type, actual_machine, version, _, program_offset, _, _, actual_header_size, entry_size, count, _, _, _ = fields
    require(elf_type == 3 and actual_machine == machine and version == 1,
            "ELF shared-object type or machine does not match packaged ABI")
    program_format = "<IIQQQQQQ" if elf_class == 2 else "<IIIIIIII"
    expected_entry_size = struct.calcsize(program_format)
    require(actual_header_size == header_size and entry_size == expected_entry_size,
            "Invalid ELF header/program-entry size")
    require(0 < count <= MAX_PROGRAM_HEADERS and program_offset >= header_size,
            "ELF program-header count/offset exceeds bounds")
    require(program_offset + count * entry_size <= len(payload), "Truncated ELF program-header table")
    load_alignments = []
    address_limit = 1 << (64 if elf_class == 2 else 32)
    for index in range(count):
        entry = struct.unpack_from(program_format, payload, program_offset + index * entry_size)
        if elf_class == 2:
            kind, _, offset, address, _, file_size, memory_size, alignment = entry
        else:
            kind, offset, address, _, file_size, memory_size, _, alignment = entry
        require(offset + file_size <= len(payload), "ELF segment exceeds file bounds")
        if kind != 1:  # PT_LOAD
            continue
        require(file_size <= memory_size and address + memory_size <= address_limit,
                "ELF load-segment memory bounds are invalid")
        require(alignment in (0, 1) or alignment & (alignment - 1) == 0,
                "ELF load-segment alignment is not a power of two")
        require(alignment <= 1 or (address - offset) % alignment == 0,
                "ELF load-segment address/file alignment is incongruent")
        if elf_class == 2:
            require(alignment >= PAGE_SIZE and (address - offset) % PAGE_SIZE == 0,
                    "64-bit ELF PT_LOAD is not 16 KiB aligned")
        load_alignments.append(alignment)
    require(bool(load_alignments), "ELF shared object has no PT_LOAD segments")
    return len(load_alignments), min(load_alignments)


def inspect_apk(path: Path) -> list[NativeLibrary]:
    libraries = []
    try:
        with path.open("rb") as source:
            expected_count, directory_offset = zip_directory(source)
            with zipfile.ZipFile(source) as archive:
                members = archive.infolist()
                require(len(members) == expected_count, "ZIP central-directory entry count disagrees")
                total_expanded = 0
                names = set()
                ranges = []
                candidates = []
                for info in members:
                    safe_member(info)
                    require(info.filename not in names, "Duplicate ZIP member path")
                    names.add(info.filename)
                    total_expanded += info.file_size
                    require(total_expanded <= MAX_TOTAL_EXPANDED, "ZIP total expanded-size bound exceeded")
                    offset, end = local_payload(source, info, directory_offset)
                    ranges.append((info.header_offset, end))
                    if info.is_dir() or not info.filename.endswith(".so"):
                        continue
                    parts = info.filename.split("/")
                    require(len(parts) == 3 and parts[0] == "lib" and parts[1] in ABIS
                            and LIBRARY_NAME.fullmatch(parts[2]) is not None,
                            "Native library is outside the supported lib/ABI/libname.so layout")
                    abi, name = parts[1:]
                    if ABIS[abi][0] == 2 and info.compress_type == zipfile.ZIP_STORED:
                        require(offset % PAGE_SIZE == 0,
                                "Uncompressed 64-bit native ZIP payload is not 16 KiB aligned")
                    candidates.append((info, abi, name, offset))
                ranges.sort()
                require(all(left[1] <= right[0] for left, right in zip(ranges, ranges[1:])),
                        "ZIP member local payloads overlap")
                for info, abi, name, offset in candidates:
                    digest = hashlib.sha256()
                    payload = bytearray()
                    with archive.open(info) as native:
                        while block := native.read(1024**2):
                            require(len(payload) + len(block) <= info.file_size,
                                    "Native payload exceeds its declared bounds")
                            digest.update(block)
                            payload.extend(block)
                    require(len(payload) == info.file_size, "Truncated native payload")
                    count, alignment = inspect_elf(payload, abi)
                    libraries.append(NativeLibrary(abi, name, len(payload), digest.hexdigest(), count,
                                                   alignment, info.compress_type != zipfile.ZIP_STORED, offset))
    except (OSError, zipfile.BadZipFile, RuntimeError, EOFError, UnicodeError, struct.error, zlib.error) as error:
        raise VerificationError("Unreadable or malformed APK ZIP/native payload") from error
    return sorted(libraries, key=lambda library: (library.abi, library.name))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    arguments = parser.parse_args()
    try:
        libraries = inspect_apk(arguments.apk)
    except VerificationError as error:
        print(f"Native compatibility gate FAILED: {error}", file=sys.stderr)
        return 1
    if not libraries:
        print("Native inventory: no packaged .so libraries; no native compatibility claim.")
    for library in libraries:
        elf_status = "16KiB=pass" if ABIS[library.abi][0] == 2 else "16KiB=unverified(32-bit)"
        zip_status = "compressed(not mmap checked)" if library.compressed else f"stored(offset={library.payload_offset})"
        print(f"lib/{library.abi}/{library.name} bytes={library.size} sha256={library.sha256} "
              f"PT_LOAD={library.load_segments} min_align={library.minimum_alignment} {elf_status} ZIP={zip_status}")
    for abi in ABIS:
        print(f"ABI {abi}: {sum(library.abi == abi for library in libraries)} packaged native libraries")
    print("Native static gate passed; dependency provenance/licences, 32-bit 16 KiB behavior, "
          "loading and device/runtime compatibility require separate evidence.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
