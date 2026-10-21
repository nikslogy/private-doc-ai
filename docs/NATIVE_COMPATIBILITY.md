# Native dependency and 16 KiB evidence

Checkpoint: 18 October 2026. **No actual APK or downloaded native library has
been inspected in this workspace.** Dependency ABI coverage, 16 KiB support,
load behavior and complete licence notices remain pending. The passing tests
below use handcrafted standard ZIP/ELF fixtures; their hashes identify only
synthetic test bytes.

## Runnable static gate

After both APKs assemble, run `scripts/check-host.sh`, or inspect a particular
artifact with `scripts/verify-artifact.sh offline <apk>` and its connected
equivalent. The artifact verifier preserves its existing merged-manifest checks
and now invokes `python3 scripts/check-native-libraries.py <apk>` before
printing the APK checksum. A native mismatch fails the artifact/host gate.

The native checker reports packaged `lib/<ABI>/libname.so` names, byte sizes,
SHA-256 values, load-segment counts, minimum ELF alignment and ZIP storage.
It prints separate counts for arm64-v8a, x86_64, armeabi-v7a and x86. A missing
ABI is reported as zero; this does not establish runtime support for that ABI.
A missing native inventory makes no native compatibility claim. Library names
and hashes alone do not identify their originating Maven dependency or licence.

For every packaged native library, the checker validates standard little-endian
ELF shared-object headers, ABI/class/machine agreement, bounded program tables
and segment file/memory bounds. For arm64-v8a and x86_64, **every PT_LOAD** must
have power-of-two alignment of at least 16,384 bytes and congruent file/virtual
addresses. Stored 64-bit `.so` payloads must also begin on a 16,384-byte boundary
computed from the ZIP **local** header, filename and extra-field lengths.
Checking only central-directory offsets would miss packaging errors.

Compressed libraries still receive ELF checks. Their compressed ZIP position
is not suitable for direct mapping, so no ZIP mmap alignment claim is made;
Android extraction policy and successful loading require the actual APK/device
checks. 32-bit libraries receive ELF validity and ABI checks, with an explicit
`16KiB=unverified(32-bit)` result. Their presence is not evidence of 16 KiB
device compatibility, nor does their 4 KiB alignment fail a 64-bit-only rule.

The script uses only Python's standard library, reads native bytes into bounded
memory and never extracts archive members or processes private documents.
Diagnostics do not echo arbitrary ZIP member names or payloads. Limits are:
2 GiB APK, 16 MiB central directory, 10,000 members, 256 MiB expanded per member,
1 GiB total declared expanded bytes, 1,024-byte member paths and 1,024 ELF
program headers. Members of at least 1 MiB with expansion over 200:1 fail.
Multi-disk/ZIP64, encrypted/unsupported compression, duplicate/unsafe paths,
symlinks, overlapping local payloads and local/central mismatches fail closed.
Unsupported input requires a reviewed parser change, not disabling the gate.

## Observed synthetic verification

`python3 scripts/test-native-libraries.py` passes **22 tests**. Cases include
both 64-bit machines and both 32-bit inventories, multiple PT_LOAD segments,
4 KiB versus 16 KiB ELF alignment, local ZIP alignment, compressed payloads,
ABI/class mismatches, corrupt/truncated headers/tables/segments, unsafe paths,
duplicates/symlinks, CRC failures and ZIP expansion/directory bounds.
`python3 scripts/test-validation.py` passes **10 mocked-tool tests** using valid
minimal synthetic ZIP containers and an intentionally invalid ZIP rejection;
these are not Android installable APKs.

## Evidence still required

1. Resolve the pinned Maven artifacts and retain actual AAR/POM/metadata and
   dependency-tree evidence. Map every packaged `.so` hash to its source
   coordinate/version, transitive/native components and complete licence
   notice. Do not infer published metadata from a source tag.
2. Run the manifest/native checker against the exact offline and connected
   APKs. Retain the output and APK digests; compare the inventories and expected
   ABI coverage. Resolve failures through compatible published dependencies or
   a reviewed build of their actual sources, without weakening checks.
3. Cross-check stored ZIP alignment with the official Android SDK command
   `zipalign -c -P 16 -v 4 <apk>`. Record any distinction between APK and future
   App Bundle delivery packaging. This Python checker is not a replacement
   for SDK installation/signature verification.
4. Run the actual parser, SQLCipher, crypto and model probes on
   the selected supported targets, including a real 16 KiB page-size target.
   Record `adb -s <explicit-target> shell getconf PAGE_SIZE`, OS/API/ABI and
   exact APK digests with the results. Static segment alignment cannot prove
   that dependency code avoids hard-coded 4 KiB assumptions, that a model
   loads, or that native calls meet memory/cancellation/privacy requirements.

Official procedures: [Android 16 KB page-size support](https://developer.android.com/guide/practices/page-sizes)
and [zipalign](https://developer.android.com/tools/zipalign). Runtime acceptance
also follows [DEVICE_TESTS.md](DEVICE_TESTS.md) and [COMPATIBILITY.md](COMPATIBILITY.md).
