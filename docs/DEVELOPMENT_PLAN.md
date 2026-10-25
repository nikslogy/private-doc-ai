# Private Document Assistant — implementation reference

Prepared for Nikit, version 1.0, 18 October 2026.

This working reference preserves the requirements read from the supplied
`Private-Document-Assistant-Codex-Cloud-Plan.md`. The environment was replaced
after that document was read and its temporary attachment path is no longer
mounted. This is a condensed transcription, not a byte-for-byte copy. The
user-supplied specification remains authoritative. Targets are proposed,
not measured performance or security guarantees.

## Product

Native Android, Kotlin, Jetpack Compose and Material 3. Three modules:
`:app` for UI/assembly, `:core` for pure Kotlin decisions, `:platform` for Android
adapters. Minimum Android API 31 subject to real runtime constraints.

Personal PDF/JPEG/PNG/UTF-8 text vault with immutable originals, local search,
page-cited document questions, on-device OCR and LiteRT-LM inference. Initial
app needs no cloud AI account, OpenAI API key, mandatory account or document
backend. Unsupported AI devices retain vault, direct search and file tools.
No production fakes or canned answers presented as document analysis.

User clarification, 25 October 2026: useful local assistance on nominal 4 GB
phones is a product target; slower responses are acceptable. Qualify small
quantized artifacts and bounded contexts before imposing a device-wide RAM
restriction. Record actual Android-reported total/available memory, process
peaks and sustained repeated-use behavior; do not present file size, a desktop
run or one emulator as universal phone support. Prefer bounded revision-pinned
retrieval and independently verified literal facts over sending whole documents
to a larger model. Keep slower low-memory modes explicit and preserve offline,
privacy, licence, cancellation and citation requirements. Capability and quality
must be evaluated separately; a small model loading does not enable unverified
answers. The current compact-model implementation/evidence is in STATUS.

An imported policy can be found as its original, questioned for its premium
with a page reference, then converted to JPG. Follow-ups pin the selected
immutable revision unless the user changes selection.

## File contract and bounds

- Retrieve original bytes with matching digest; clarify ambiguous policies.
- PDF to JPEG produces one image per selected page with validated count,
  order, geometry and orientation. Photos to PDF require explicit order.
- Money uses exact decimals and named fields. Premium, pre-tax premium, tax,
  insured value, sum insured and deductible are distinct. Clarify “amount”.
- Crop/rotate/compress produce new derivatives with parent references.
  Measure actual compression bytes; report when a target cannot be met.
- Preserve text/vector PDFs. Rasterization needs an accepted, labelled
  image-PDF operation with disclosed search/signature/interactive losses.
- Arbitrary PDF/Word rewriting and secure redaction are outside V1.
- Share only via explicit user-initiated Android Sharesheet; user chooses
  recipient. Deletion confirms the exact item and removes derived records.
- At most 25 MiB per import, 50 PDF pages, 20 megapixels per source image,
  4 megapixels per rendered page, one heavy OCR/render/model task at once.
- Bound parse time, decoded pixels, memory, token budgets, retries and depth.
  Corrupt/oversized/unsupported inputs produce specific safe failures.

## Boundaries

`VaultRepository`: import/open immutable revision/store derivative/delete.
`DocumentParser`: native text, page geometry, rendering.
`OcrEngine`: supplied page to text with source coordinates.
`SearchRepository`: current authorized local records.
`LocalModelEngine`: load/infer/cancel/release/capability.
`ToolExecutor`: validated typed actions against explicit revisions.
`BackupStore`: ciphertext objects and versioned manifests.
`ExportController`: particular artifact, user-selected destination.

Room with maintained `net.zetetic:sqlcipher-android`, Tink Streaming AEAD
for objects and AEAD for envelopes, Android Keystore/system authentication.
Bundled ML Kit Latin/Devanagari OCR; a maintained Pdfium binding behind an
interface; Android PDF creation APIs; LiteRT-LM adapter; encrypted SQLite
lexical search first, optional separately validated local embeddings.
Use SAF/scoped grants, WorkManager only when eligible, no broad storage access.
Pin actual versions; document licences, native libraries, 16 KiB support,
JDK/AGP/Gradle/Kotlin/SDK tuple. Never disable lint/native checks to pass builds.

PDF plaintext must be bounded memory or a reviewed secure random-access path.
A pipe is not seekable. Plaintext parser disk caches remain an unresolved
privacy decision rather than a silent weakening of requirements.

## Privacy and crypto

Encrypt originals, derivatives, thumbnails, OCR, fields, chunks, vectors,
chat and sensitive metadata. Opaque filenames, encrypted titles/digests.
No remote OCR/inference/embedding, content analytics, crash attachments or
cloud fallback. Logs contain safe codes, coarse timing and categories only.

Provide offline and connected build flavours. Offline merged manifest has
no INTERNET permission, including transitive permissions. Connected permits
explicit model download and later ciphertext backup, never private processing.

Generate a random vault root wrapping key. Separately generate SQLCipher
secret and Tink keysets; AEAD-wrap using purpose-bound AAD. Protect local root
with an Android Keystore wrapping key requiring configured system auth.
Bind vault/revision/purpose/format identities through AAD. Library primitives
manage randomness; no home-grown ciphers/nonces or short-PIN key derivation.
Document envelope format, IDs, rotation, migrations and authentication failure
in CRYPTO_DESIGN.md before finalizing. Recovery is explicit, high-entropy,
user-held and separate from backups; no master support key.

Lock stops jobs, closes DB, releases inference/KV state, invalidates previews.
Use a short documented background timeout with explicit export exception.
Exclude all private data/models/caches from cloud backup and device transfer.
Reconcile interrupted writes and expire exports on restart. Never give a
parser/model a whole-vault key. Use restricted parser capabilities where possible.
No perfect JVM zeroisation, compromised-OS protection or forensic erasure claims.

Exports use read-only expiring URI grants; no broad FileProvider root.
Any necessary plaintext export cache is app-private, excluded, explicitly
authorized and session-limited. Recipients may retain copies. Imported-copy
deletion does not delete the source provider file or historical backups.

## Data and evidence

Encrypted entities: Document, immutable DocumentRevision, SourceReference,
PageEvidence, ExtractedField, TextChunk, versioned Embedding, Conversation,
Operation, BackupSnapshot. Preserve identifier leading zeros, raw formatting,
currency/units and ambiguous dates. New artifacts reference parents.
Jobs: queued/running/succeeded/failed/cancelled/waiting_for_unlock; successful
retries cannot duplicate outputs.

Validate URI/type/signature/limits; stream encryption while hashing; publish
DB only after object completion; deduplicate exact originals. Prefer native
PDF text, OCR missing text. Failed OCR retains original and marks indexing
state. PDF passwords stay transient, never in chat/logs.

Retrieval ranks explicit sources, exact identifiers, fields and lexical
matches before semantics. “Current” means confirmed identity/coverage dates,
not import timestamp. Conflicting/missing facts trigger selection. Preserve
Unicode combining marks and evidence mapping. Initial scale target: 500
documents or 5,000 pages, subject to benchmarks.

For extraction the model proposes evidence; application verifies span,
revision, page and stored numeric field. Missing, unsupported and ambiguous
answers abstain or clarify. Summaries use bounded passes and valid page refs;
partial analysis cannot be labelled complete.

## Models and actions

Curated catalogue: real immutable identity/revision, bytes/SHA-256, licence
acceptance/terms, format/runtime/tokenizer/template, evaluated languages/tasks,
backends/resource profile and context bounds. Never invent hashes or licences.
Verify download/import before load. Unknown models are experimental. LiteRT-LM
does not imply GGUF compatibility. CPU and accelerators need actual probes.
Without model: direct search/forms/tools, clear unavailable AI status.

Closed actions: SEARCH, GET_ORIGINAL, ASK_DOCUMENT, SUMMARIZE, PDF_TO_IMAGES,
IMAGES_TO_PDF, COMPRESS_IMAGE, COMPRESS_IMAGE_PDF, CROP_IMAGE, ROTATE_IMAGE,
REQUEST_EXPORT, REQUEST_DELETE, CLARIFY. Validate authorization, IDs, bounds,
ambiguity, lock and explicit current user intent for sharing/deletion.
Model IDs are suggestions; app resolves real authorized revisions.
No automatic tool execution, generated shell/JS/paths/URLs/downloaded tools.
Document contents are evidence, never privileged instructions. Q&A gets
excerpts without mutation tools. Propagate cancellation to native runtime.

## UX

Real welcome, setup/unlock, documents/import/search, chat/source clarification,
viewer/evidence, manual tools/preview, offline assistant/model manager,
backup/recovery and privacy/settings screens. Externalize English UI strings.
Model availability and backup state are distinct. OCR script support is not
proof of conversational Hindi/Marathi quality; evaluate separately.

## Ordered milestones

| ID | Work and evidence |
| --- | --- |
| M0 | Reproducible build; pinned compatibility/licences; real parser, crypto, SQLCipher and runtime probes |
| M1 | Encrypted vault/DB, system lock/unlock, immutable revisions; restart, wrong-key/tamper and leakage tests |
| M2 | Import UI, parsing/OCR/evidence/encrypted thumbnails, limits/cancellation and failed-OCR originals |
| M3 | Search/fields/selection and revision-pinned follow-ups; policy ambiguity fixtures |
| M4 | Verified model manager and actual LiteRT-LM inference/cancel/device capability |
| M5 | Typed intent, verified QA/summaries, injection controls with real model |
| M6 | Validated derivative tools/previews/system sharing and grant expiry |
| M7 | Portable picker-based encrypted backup/recovery, staged verified fresh-install restore |
| M8 | Hardening, offline/device evaluation and reproducible installable test APK; local release gate |
| M9 | Later opt-in direct Drive encrypted single-device backup/restore |
| M10 | Later separately assessed WhatsApp Business eligibility, then approved paired relay if eligible |
| M11 | Separately implemented/evaluated Office formats and advanced edits |

Execute in order. UI existence does not complete a milestone. Track implemented,
host-tested, device-tested, externally verified and blocked separately. Hardware
absence permits runnable probes and independent progress, never release claims.
Drive system-provider import is separate from M9. No confidential Google client
secret in APK; narrow per-file official authorization later. WhatsApp first uses
share-in/share-out; no unofficial Web automation or inference of recipient.

Backups: random recovery material, separately held secret, consistent encrypted
snapshot, immutable objects before completed encrypted manifest. Restore to
staging; authenticate/verify completeness/schema before activation. Wrong key,
partial or tampered data cannot replace an existing vault. New Keystore wrapper
on new phone. Historical deletion retention and freshness limits disclosed.

## Verification and deliverables

Scripts: bootstrap-android.sh, check-host.sh, check-device.sh, check-model.sh,
verify-artifact.sh. Official pinned downloads/checksums; SDK licence respect;
idempotence, memory/disk checks; no routine model download or production secrets.
Run :core:test, app offline/connected unit/lint/assembly and platform tests.
Require explicit device targets; discover rather than assume emulator/KVM/GPU.

Synthetic fixtures: current/expired policies, two vehicles/owners, duplicates,
premium/tax/insured-value distinctions, Indian numbers, leading zeros/dates,
native/scanned/mixed/rotated/blank/corrupt/password/oversized inputs,
Devanagari, missing/conflicting facts, prompt injection, interrupted lifecycle.
Maintain source spans/pages/original digests and holdout evaluation. Full release
suite requires >=120 labelled questions/actions with category denominators.

Release targets (unmeasured until recorded): retrieval >=95% unambiguous top-1;
supported numeric QA >=98% precision and >=80% clean-answerable coverage;
faithful citations, byte-identical originals, valid transformations, wrong
key/AAD/vault/ciphertext rejection, no disk/log canary leakage, working recovery,
lock/cancel/restart and injection rejection, merged offline permission proof,
16 KiB/native compatibility. Any confidently wrong release-fixture amount blocks.

At least two physical phone tiers plus API/native compatibility targets.
Record model/hash/backend/device/OS/RAM/thermal conditions and corpus. Reference
targets: search p95 <1 s; original retrieval p95 <2 s; warm QA first text <5 s,
completion <20 s; cancellation acknowledgement <1 s; bounded memory/no crashes.
Connected import/OCR/search/QA traffic inspection with downloads/backup off.
No model/phone evidence means no inference/accuracy/performance release claim.

Repository: README, AGENTS, this plan, STATUS, ARCHITECTURE, CRYPTO_DESIGN,
COMPATIBILITY, PRIVACY, DEVICE_TESTS, EVALUATION, decisions, real models.json,
synthetic fixtures, scripts and CI. Keep SDKs/weights/keys/customer data out.
STATUS records exact commands/results/unrun checks/artifacts/blockers/next task.
Do not publish/deploy/app-store release or enable a live bot in this task.

## Primary references

- https://developers.google.com/edge/litert-lm/android
- https://github.com/google-ai-edge/gallery
- https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- https://developer.android.com/privacy-and-security/keystore
- https://developers.google.com/tink/streaming-aead
- https://www.zetetic.net/sqlcipher/sqlcipher-for-android-migration/
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/identity/data/autobackup
- https://github.com/johngray1965/PdfiumAndroidKt
