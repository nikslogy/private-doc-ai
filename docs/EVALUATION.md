# Evaluation and evidence

Code9 responds to user-reported code8 repeated greetings and rejected document
questions. The native adapter now receives separate system/history/current-user
messages. A retained desktop experiment reproduced greeting-like legacy responses
on4/5 questions after greeting history; role separation produced distinct replies
but still factually wrong general answers. Seven fresh desktop spot checks passed
varied/general/source/missing/summary cases. These are synthetic exploratory checks,
not representative quality or Android evidence. Forcing citation formatting caused
a fabricated owner, so the accepted prompt retains missing-evidence abstention;
uncited model explanations show all exact supplied excerpts as unverified context.

Core adds20 retrieval/prompt/validator cases and expands role-prompt cases, reaching
136 core tests. The focused core command passed all136 freshly, with no failures
or skips. New Android controller fixtures use actual suggestion resources, distinct
typed input/history, broader source questions, invalid model output, bounded
all-documents search beyond the initial read cap, source invalidation and uncited
context disclosure. Stable CI requires18 controller cases per variant. The real
model probe additionally requires5 actual varied/source/missing/summary semantic
checks through the verified gateway, alongside history recall, cancellation, model
hashes and bounded file canaries. STATUS records which checks actually executed.

A valid source span or supported numeric token does not establish field meaning
or entailment of generated prose. General/dialogue quality, multilingual retrieval,
long documents, adversarial source instructions and cross-document ambiguity need
separate held-out evaluation. No catalogue task/language accuracy is claimed.

The M3 selected-source label reader adds synthetic core extraction cases and
augments the real authenticated vault lifecycle probe with pinned reads, exact
field citations, conflicting premiums and unchanged original bytes/ciphertext.
Actual execution results are recorded in STATUS. This is grammar/boundary
verification, not a policy-QA or representative-layout accuracy benchmark.
Phone UI/source-switch races and full held-out evaluation remain pending.

This repository provides an encrypted-evidence/search development source checkpoint and a small deterministic synthetic corpus. No representative document/model benchmark has been run. Source checks and unit tests establish narrower properties than complete Android privacy or release readiness.

Run `python3 scripts/test-fixtures.py` for fixture reproducibility, digests, two-page PDF structure, PNG integrity, and exact UTF-16 evidence spans. Run `python3 scripts/security-check.py` for source manifest/export/logging/execution policies. Run `scripts/check-host.sh` after provisioning the pinned toolchain for core/platform/flavor unit tests, lint, assembly, and inspection of each actual merged APK manifest. A missing toolchain is a failure with setup instructions, never a passing or silently skipped suite. `verify-artifact.sh` prints the APK SHA-256; this does not verify provenance or device behavior.

The six synthetic files and seven illustrative ground-truth questions in `test-fixtures/generated` cover a native two-page vehicle policy, an expired policy for another owner/vehicle, an exact decimal invoice total, a simple raster scan, prompt injection text, and corruption. Policies distinguish total premium, pre-tax premium, tax and insured value, preserve leading-zero identifiers and ISO dates, and include Indian number formatting, an unresolved date and a Marathi note. Fixtures retain page text, labels, immutable original digests, selected document scope, and citation offsets. `answer_contains` is a manual development expectation; the integrity test does not run retrieval, OCR, or model inference. There is no automatic answer quality score. The bitmap scan is deliberately simple and does not represent phone photos. `test-validation.py` uses mocked external-tool output to test fail-closed script gates; it does not inspect a real APK.

Before release, create a separately held, authorized evaluation corpus with at least 120 labelled questions/actions as required by the development plan. Track denominators by category, including clean answerable numeric facts, missing facts, ambiguity, conflicting policy revisions, wrong source selection, leading zeros, Indian number formatting, date ambiguity, native/scanned/mixed/rotated PDFs, blank/password/corrupt/oversized documents, Devanagari, prompt injection, and interrupted lifecycle operations. Keep tuning examples separate from holdout evaluation. Preserve labels, exact source revisions/pages/spans, intended actions, expected abstentions, and original hashes.

Record retrieval top-1 accuracy for unambiguous selections; numeric precision and clean-answerable coverage separately; citation validity; original byte identity; transformation geometry/order/digests; wrong key/AAD/vault/ciphertext rejection; no plaintext canary leakage to disk/logs; lock/cancel/restart; export grant expiry; fresh-install recovery; and connected-build traffic. Unsupported and ambiguous cases should abstain or clarify and must remain visible in the denominators. A confidently wrong amount blocks release regardless of aggregate scores.

The plan's proposed thresholds (retrieval at least 95%, supported numeric precision at least 98%, clean-answerable coverage at least 80%) are targets, not current results. Performance targets likewise remain unmeasured. Report per-device/model/backend p50/p95, memory, thermals, cold/warm state and failures, with the precise APK/model hashes and corpus version. Use at least two physical phone tiers. `check-model.sh` requires a real registered artifact and explicit device; its M0 load probe does not establish inference, active cancellation, accuracy, or conversational language support.

Use [DEVICE_TESTS.md](DEVICE_TESTS.md) for the hardware procedure. Record actual completed, failed, skipped, and unrun checks in `STATUS.md`; do not infer hardware outcomes from host tests, a source adapter, or a screen being present.

Checkpoint 2 adds 19 authored, unrun Kotlin lexical-search tests (55 core tests
total), three authored real encrypted migration/search integration tests and
image/index/OCR-task device probes. Search cases cover quoted literal FTS input,
Unicode combining marks, NFC with original UTF-16 offsets, identifier leading
zeros/grouped numeric strings and grounded bounded snippets. These are not
retrieval-accuracy/financial-QA results. Actual host validation comprises six
fixture tests, 22 synthetic native ELF/ZIP tests and ten mocked validation-gate
tests (38 passing Python tests). No APK or model result is implied.

ML Kit's real adapter is test-only while its diagnostics behavior is unresolved;
both production variants exclude ML Kit and use the separately reviewed local
Tesseract adapter. A development recognizer test
cannot establish an approved telemetry-free production path or conversational
Hindi/Marathi quality. See the decision record and NATIVE_COMPATIBILITY.md.
