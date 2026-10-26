# Code9 context update

The compact model identity and existing import remain unchanged. Code9 repairs
chat roles and adds a fixed document1024-context/192-output-token profile; general
chat stays512/128. Source excerpts remain on device. This does not improve model
knowledge by itself or establish universal4GB compatibility. See [CHAT](CHAT.md)
and [STATUS](STATUS.md) for capability and actual validation.

# Experimental compact on-device model setup (M4)

Code7 / 0.4.2-dev added the compact model for the 4 GB device target. Code8 /
0.5.0-dev reuses the same verified installation for actual chat in Assistant.
This setup screen retains its separate fixed synthetic CPU diagnostic. Supported
printed-document questions and extractive fact summaries are in the Assistant
source picker; unrestricted document synthesis remains unavailable. See STATUS for actual
Android build/runtime results and pending physical-phone checks. A RAM target
is not a guarantee for every phone with that advertised capacity.

In Assistant choose **Set up on-device AI**, then the recommended **Qwen 3 0.6B**
entry. Its model-file button opens the system browser for the exact immutable
upstream artifact. Download it there, return, acknowledge the licence and choose
**Import model file**. Do not reuse the old 1.6 GB Qwen 2.5 download: it is a
different model. The app adds no model-download network path, and the offline
APK retains no INTERNET permission. The browser/document provider can use its
own network connection.

The compact file is 347,251,840 bytes, with the exact revision/SHA-256 in
config/models.json. Installation temporarily needs twice that size plus 64 MiB
of free app storage, separately from the downloaded source. No inference weights
are included in source, APKs, workflow artifacts or caches. Only the exact
reviewed bytes are accepted; a moving upstream file with the same name is not
an alternative. Its experimental registration has no evaluated languages/tasks.
[Decision 0007](decisions/0007-low-memory-model.md) records actual upstream
metadata, conversion, licence and runtime provenance.

The former Qwen 2.5 1.5B / runtime-0.9 record is retained unchanged. Previously
installed slots/receipts remain recognized and preserved. Download/import/test
for that retired runtime profile are disabled; explicit model removal remains
available. Removing it does not delete a source download or vault documents.
No old model is automatically deleted, replaced or loaded under a new runtime.

## Verified installation boundary

An SAF-selected input is untrusted and may be a private document. The installer
hashes it while writing Tink Streaming AEAD ciphertext under a fresh memory-only
key, which is never persisted. Only an exact expected public size/hash match
permits authenticated decryption into plaintext public weights, a second hash,
fsync and atomic publication. Wrong private inputs never enter plaintext model
staging. Cancellation/rejection removes only recognized staging files; crash
recovery discards ciphertext without a key. Unknown files are preserved.

Reviewed metadata, receipts and models live in noBackup/local-models-v1. Source
URIs/names are not stored. Symlinks/unexpected slot contents fail closed. A
process-wide coroutine mutex holds each verified model lease through native
load, generation and cleanup, excluding store mutation; the token expires on
return. These controls protect the normal app path, not a compromised OS or
malicious code in the same process. Corrupted slots require explicit removal
before reimport.

## Bounded compact CPU test

The real pinned LiteRT-LM 0.16.0 adapter uses two CPU threads, fixed general512/128
or document1024/192 context/output-token budgets, thinking disabled, :nocache, no tools and automatic
tool calling disabled. The INT4 artifact has an exported 1280-token context and
several prefill signatures, instead of the old INT8 artifact's 4096. Smaller
configured context alone does not establish smaller native allocation; measure
the actual model/runtime/backend. GPU acceleration is not implemented here.

Input totals at most1024 general or3072 document UTF-16 units; output is at most2048, checked after native
allocation. Operations allow up to five minutes for slower phones. A fresh
conversation/engine is closed per operation, and a joined watchdog repeatedly
requests cooperative native cancellation. Synchronous send must return before
native handles close. Initialization/hangs have no hard interruption guarantee.
Closing the engine does not guarantee immediate OS RSS reclamation: the separate
Linux native smoke observed allocator-retained memory after close. Process
isolation remains a future hardening gate. UI lock clears state and revokes work;
safe native cleanup can return later.

The exact compact artifact/runtime profile requires a 64-bit process, at least
3 GiB Android-reported total memory, 1536 MiB currently available and no Android
low-memory state. Nominal 4 GB phones report less than 4 GiB usable total. These
are experimental admission settings, not a tested universal minimum. A check
runs before model verification and again inside the shared heavy-work gate
immediately before native loading; rejection displays the selected model's
actual/required memory. There is no override that loads an unknown profile or
the retired heavy model without review.

The diagnostic uses a fixed synthetic prompt only, with no vault key, source
document, extracted evidence or user question. Displayed text is actual,
unverified model output; it cannot authorize actions. Chat/KV state is not saved.
The smaller model's English/Hindi/Marathi quality and document accuracy are not
established by a successful runtime test.

## Actual evidence and next checks

The full exact public download passed byte-count/SHA-256 verification. Two real
Linux CPU smoke runs generated bounded text with the official pinned 0.16.0 C
API. The measured native child used about 2.56 GiB process HWM, versus the old
Android experiment's 8.14 GiB; these are different platforms, not a controlled
speed/memory comparison or proof of Android support. Exact desktop scope and
retained-memory observations are in decision 0007.

Routine host/stable checks use metadata and tiny synthetic installer/privacy
fixtures without downloading an inference model. The separate explicit model
workflow downloads/hashes the reviewed public file to disposable scratch and
boots an API 36 x86_64 emulator with 4096 MiB nominal RAM. It requires the actual
application memory policy, denial-before-load, one fresh non-skipped generation/
cancellation test, before/after model hashes, bounded file-canary scans and
numeric process memory samples. See STATUS and the compact-native checkpoint for
the actual verdict. Weights/emulator userdata are cleaned and excluded from
workflow artifacts/caches. Run 40168449811 passed one fresh test with no skips on
this constrained target: Android reported 4,014,272 KiB total, 2,457,980 KiB available
before the test; both real operations passed the application's admission hook.
The sampler collected 84 numeric samples with zero errors. Observed VmHWM was
2,653,343,744 bytes (~2.47 GiB); complete generation took 54,688 ms and the separate
cancellation operation 21,783 ms, both including native initialization. These are
emulator observations, not a phone minimum, speed or cancellation-latency claim.

After the signed update and explicit emulator results, perform the phone import/
tamper/cancel/lock/restart and same-key update procedure in DEVICE_TESTS.md. Record
exact APK/model/device identities, actual total/available RAM, latency and
resource behavior. The user's iQOO Neo 10 has 12 GB physical RAM and 256 GB storage,
and reports the compact synthetic test completed. The user reports code8 repeated greetings and rejected document questions.
Code9 repairs role handling and adds bounded model-assisted source questions and
summaries; see [CHAT.md](CHAT.md). Independently measured phone behavior/memory/
latency and representative source accuracy remain to be checked. Narrow synthetic file-canary checks do not establish complete
filesystem, logs, traffic, memory, thermal, accuracy or release readiness.

The code8 native chat experiment 40191631336 separately passed actual chat prompts,
short-history recall through LocalChatGateway and native cancellation on the
4 GiB emulator; measured process VmHWM was ~2.51 GiB. This preserves the exact same
model/runtime/receipt identity. Use [CHAT.md](CHAT.md) for normal conversation;
repeating the setup diagnostic is not the normal chat flow.
