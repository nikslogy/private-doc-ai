# Architecture checkpoint

The project uses three modules: pure Kotlin `core`, Android `platform`, and
Compose `app`. Dependencies flow app → core/platform and platform → core.
Production sources contain no fake vault, generated answer or remote model path.

## Current request flow

Device credential/strong biometric success allows `VaultUiModel` to request
`VaultController.unlock`. The controller opens purpose-bound key envelopes,
creates a Room database through SQLCipher, reconciles orphan ciphertext and
publishes an unlocked session. UI metadata exists only in memory until lock.

An SAF import streams directly into Tink Streaming AEAD staging storage while
hashing. A bounded plaintext buffer is then decrypted in memory for file
validation. Deduplication uses encrypted database digests. A durable rename
precedes metadata publication. The original object and revision UUID are
immutable. One original revision per document is currently supported.

Import persists native page text, geometry, source spans, extractor version and
index state in the same encrypted transaction as document metadata. Schema 2
adds page evidence, source spans and FTS4 tables; migration 1→2 keeps original
identity/objects and marks prior documents not indexed. Explicit native-text
extraction can index these documents after unlock.

Content search tokenizes raw pages and queries identically in Kotlin. Tokens
retain combining marks, identifiers and numeric separators; only NFC/case are
canonicalized for matching. Their UTF-8 hex encodings enter SQLCipher's FTS4
index, avoiding SQLite Unicode-tokenizer differences. This encoding is not
encryption: SQLCipher supplies the storage boundary. User MATCH syntax cannot
enter the generated quoted all-terms query. Hits are independently checked
against raw source text and return exact UTF-16 quotes from an immutable revision.
Search is bounded lexical matching and does not infer policy validity or facts.

The Assistant tab's manual facts reader pins an explicitly chosen document and
revision. A bounded core label grammar returns literal values with validated exact
line evidence, preserves raw identifiers/dates/decimals, and leaves conflicting
values unresolved. Generic labels retain generic meanings. UI session/generation
guards reject stale results; lock, deletion and reindex invalidate transient facts.
No extracted-field plaintext file or database migration is introduced. See
[DOCUMENT_FACTS.md](DOCUMENT_FACTS.md) for limits and scope.

The UI combines name/type matches with content hits and opens a page-text
evidence viewer showing page, revision and extraction method. It does not yet
render a full PDF viewer. Image validation includes bounded sampled decode,
framing/PNG CRC and EXIF transforms. PDF/render/image work is serialized with
the runtime probe; native hard timeouts and process isolation remain pending.

User-confirmed export pins a `VaultDocument` and its immutable original. The
system picker creates the chosen external destination; the controller decrypts
through EOF and verifies bytes/digest. On failure the UI attempts to remove
partial output and reports failed cleanup. The short background timeout also
applies during the picker; long selections can require a new unlock/export.
This is file-picker export, not a completed Sharesheet/temporary-URI subsystem.

Deletion is a direct confirmed UI operation. Evidence/FTS removal and metadata
deletion share a transaction, followed by object unlink; restart cleanup handles
orphan ciphertext. It does not remove original
provider files or external copies. Core's single-use export/delete permits are
available for the future typed-action executor; no model executor is connected.

## Domain safety

`core` preserves explicit document/revision references and decimal source text.
Evidence validation checks exact revision/page/UTF-16 span/quote. Numeric token
checks cannot establish semantic field classification; application field storage
and real QA evaluation remain necessary. The label reader supplies conservative
field candidates, not model-based semantic verification. Actions are a closed set with bounded
pages, image geometry, compression and rasterization consent. Model actions do
not include export/delete. Follow-ups pin a prior selected revision. The domain
heavy-job gate is available for future orchestration.

## Lifecycle and unavailable capabilities

The Activity sets FLAG_SECURE, listens for screen-off and schedules a 15-second
background lock. Lock invalidates UI state, cancels its operation and closes the
database; native/parser and ContentProvider interruption is cooperative. No
decrypted keys are persisted for a background task. A process-wide vault mutex
prevents another controller's recovery from racing publication.

`OfflineDocumentIndexer` accepts a supplied original and optional OCR engine,
never a vault key. The new Tesseract adapter receives only a bounded memory
bitmap, geometry, requested script and a cancellation predicate. A separate
public-model store verifies bundled weights before native initialization. Each
page gets a fresh engine with supported debug/output suppression checked before
pixels. A joined watchdog requests cooperative stop; a native hang still needs
future isolation. The indexer validates page identity, script, exact offsets,
boxes, states and budgets before encrypted persistence. Failed/empty OCR retries
retain earlier useful evidence and mark a failure. Native PDF text stays preferred.
ML Kit's real adapter remains instrumentation-only due unresolved diagnostics;
neither production flavour includes that SDK. Its Task-lifetime probe is separate.

M4 source adds a public-model installer using reviewed exact size/hash and current
licence acknowledgement. Unknown input is staged only as Tink ciphertext under
an ephemeral memory-only key; hash-verified public weights may then be decrypted
and published atomically. A process mutex protects verify-to-load leases across
native work. Document/provider names and URIs are never persisted. A legacy Qwen
artifact is registered as experimental with no evaluated languages/tasks or QA.

The real LiteRT-LM CPU adapter uses a 512-token context, no cache/tools, bounded
input, post-allocation output checks and synchronous send with a joined native
cancellation watchdog. Lock clears UI state and requests cancellation. Native
initialization/hangs have no hard cancellation guarantee. UI generation uses
only a fixed synthetic prompt; actual validation is recorded in STATUS. Synthetic
OCR Android verification passed; physical checks remain open.
There is no chat/QA executor, full PDF
viewer, derivative executor, backup store or recovery flow yet. These are
ordered future milestones, not capabilities implied by interface names.

See [CRYPTO_DESIGN](CRYPTO_DESIGN.md), [PRIVACY](PRIVACY.md),
[COMPATIBILITY](COMPATIBILITY.md) and [STATUS](STATUS.md).
