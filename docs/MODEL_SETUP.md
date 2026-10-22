# Experimental on-device model setup (M4)

This development slice installs reviewed public weights and offers a fixed
synthetic CPU generation test. It does not enable document questions/summaries.
See STATUS for actual build/runtime results; source code and installation alone
are not device, privacy, language-quality or document-accuracy evidence.

In Assistant, choose **Set up on-device AI**. The model file button opens the
system browser for the exact immutable upstream artifact. Download it there,
return to the app, acknowledge its licence and choose **Import model file**.
The app itself adds no model-download network path. Android document providers
and the chosen browser can use their own network access. The offline APK retains
no INTERNET permission.

The legacy Qwen 2.5 1.5B file is 1,597,931,520 bytes. Temporary installation needs
at least twice that size plus 64 MiB of free app storage, separately from the
downloaded original file. No inference-model weights are included in source/APKs/artifacts.
Only the immutable revision/hash in config/models.json is accepted; a newer file
with the same filename has different embedded metadata and is rejected. Its
experimental registration has no evaluated languages/tasks. Decision 0005
records the upstream runtime reference, original-versus-new artifact distinction
and actual licence sources; no Gemma account/licence gate is involved for Qwen.

An SAF-selected input is untrusted and could be a private document. The installer
hashes it while writing Tink Streaming AEAD ciphertext under a fresh key held only
in memory. It never persists that key. Only after exact expected public size/hash
match does it authenticate/decrypt into plaintext public weights, hash again,
fsync and atomically publish the slot. Cancellation or rejection removes only
recognized staging artifacts; crash recovery discards ciphertext with no key.
Unknown/unrecognized files are preserved. Previous verified slots are never
silently replaced. Corrupted installations require explicit model removal before
reimport; that action does not delete source files or vault documents.

Reviewed metadata, receipts and model files live below noBackup/local-models-v1.
Source URIs/names are never stored. Symlinks and unexpected slot contents fail
closed. A process-wide coroutine mutex holds each verified model lease through
native load, generation and cleanup, excluding store mutation. The token expires
on return; escaped tokens cannot access their file afterward. This protects the
normal app path, not a compromised OS or malicious code within the same process.

The real LiteRT-LM adapter uses CPU/two threads, a configured 512-token combined context,
:nocache, no tools and automatic tool calling disabled. Input is at most 1,024
UTF-16 units; output is at most 2,048, checked after native allocation. A fresh
conversation/engine is discarded after each operation. The configured context
does not establish the exported model's actual KV allocation or a measured memory
bound. A watchdog requests native
cancellation repeatedly; the synchronous send must return and the watchdog must
join before native handles close. Initialization has no interrupt entry point;
a deadline cannot forcibly kill a native hang. UI lock clears model state and
revokes work immediately, while safe native cleanup may return later.

The phone UI applies a conservative 64-bit/6 GiB total-memory/free-headroom guard.
It reads actual Android memory state. This is application configuration and does
not establish a tested model minimum or support on the reported iQOO Neo 10.
The test uses a fixed synthetic prompt only; it receives no vault key, source
document, extracted evidence or user question. Its displayed text is actual
unverified model output and cannot authorize any action. No chat/KV state is saved.

Routine host/stable checks run metadata and tiny synthetic installer/privacy
tests without downloading an inference model. The separate explicitly requested
model-runtime-probe workflow downloads/size-hashes the exact public artifact to
runner scratch, boots a measured-resource API36 emulator with 6 GiB RAM and requires a real,
non-skipped generation/cancellation test. The test observes native cancel calls
and a failing synchronous send during the cancellation experiment, rechecks the
model hash and scans bounded app files/cache/noBackup for a synthetic canary.
Only the independently hash-verified public model is excluded from that scan.
It does not establish complete filesystem/log/traffic privacy, hard timeouts,
phone cancellation latency or semantic answer quality. Its host must have at
least 8 GiB currently available before emulator startup. These setup guards are
configuration, not measured model minimums. Weights and emulator
userdata are removed and excluded from artifacts/caches.

After CI: perform explicit phone installation, import/tamper/cancel/lock/restart
and synthetic generation checks, collect exact model/APK/device identities and
measure resource/cancellation/privacy behavior. Model-based document QA remains
gated until real device evidence and the M5 citation/evaluation layer exist.
