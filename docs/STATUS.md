# Development status

## Checkpoint 13 — chat roles and local document passage answers

[Stable-signed v0.5.1-dev / code 9 APKs](https://github.com/nikslogy/private-doc-ai/actions/runs/40290851665/artifacts/12636309160)
passed the complete build and 114 Android executions, with no failures/skips.
Install the offline APK over **Private Vault Preview**, without uninstalling or
clearing data. Preview IDs/signer and the exact compact model receipt remain
unchanged. An intact existing model installation needs no reimport/download.
The separate real-model code 9 Android verdict is tracked below.

The user reports code 8 repeated a greeting for different general questions and
rejected selected-document questions, including suggestions. Its earlier model
load/name-recall/controller passes did not establish usable phone conversation.
The old handwritten prompt with greeting history reproduced greeting-like replies
on 4/5 retained desktop questions. Native role-separated messages produced distinct
answers; general factual errors remained. Code 9 keeps system/history/user roles
separate and preserves complete recent pairs/current input.

Document chat now retrieves bounded relevant passages from readable source text,
with an explicit **Ask my documents** scope as well as one-document selection.
Existing encrypted lexical search prioritizes sources for larger libraries;
reads retain at most 16 revisions/200,000 UTF-16 units and four 480-unit excerpts
enter the local model. A fixed document 1024/192 context/output-token profile
complements general 512/128. Broader questions and summaries no longer depend on
a tiny policy/invoice question list. Selected-document printed numeric/identifier
questions keep direct literal extraction, raw formatting, conflict/partial rules.

Generated explanations are labelled AI and shown beside exact quoted context and
page links. Invalid generated references/numbers or known canned greetings fall
back to actual excerpts. Uncited explanations show every supplied excerpt with a
specific context-only note. A matching quote/number does not prove generated
meaning. Forcing citation formatting in a desktop trial invented ownership;
that prompt was rejected. Missing-evidence abstention remains required. Summaries
state partial scope; current document questions do not receive prior document
chat history. Large libraries, lexical paraphrases and small-model understanding
remain limitations, with no representative accuracy claim.

Chat/source plaintext remains in unlocked memory only. General history excludes
document replies. Lock/clear/source changes/delete/reindex revoke work and
citations; all-documents scope changes invalidate pending results. Native tools,
remote processing, plaintext persistence and model action authority remain absent.
Originals are immutable. See [CHAT](CHAT.md), [PRIVACY](PRIVACY.md).

| Gate | Actual result |
| --- | --- |
| Focused core | 136 fresh passing JVM cases,0 failures/errors/skips, 47s |
| Local full host | PASS in 5m27s; 129 Python checks, 192 passing JVM XML cases (136 focused core reused UP-TO-DATE + 28 fresh platform per variant); app JVM NO-SOURCE |
| Local Android compile/lint/assembly | All app/platform instrumentation Kotlin, lint, production dependency gates and both assemblies passed |
| Stable CI 40290851665 | Complete PASS; 129 Python checks/fullhost, actual 114 Android executions, none skipped |
| Controller suggestions/input/source flow | 18 actual Android cases per variant; actual resource presets, distinct typed requests/history, source questions, invalid output, >16-source shortlist, uncited context, citation numbering and stale-work rejection |
| Platform + credential lifecycle | 38 platform and 1 actual emulator credential lifecycle per variant, passed |
| Real-model Android code 9 | PASS run 40290851638 attempt 2: one fresh test, five actual varied/source/missing/summary checks plus history recall, cancellation, hashes and narrow file canaries |
| Identity/permissions/native inventory | Both pinned Preview IDs/signer/v2/official zipalign passed; offline no INTERNET; no production ML Kit; 18 64-bit libraries/APK static 16 KiB pass, 16 32-bit unverified |

Initial local fullhost caught an unsupported AN-overview summary variant; fixed
and all 136 core cases freshly passed. A later host launch hit sandbox socket
creation denial before Gradle; the sandboxed network-grant run above passed.
No local device/KVM was available and no missing hardware suite is called a pass.

### Exact delivered identity

- Production source 5dbbcc3f48d128a00de146fa74de60db6fa4543e.
- Built stable/model marker f1c15403461f286be5c6094ef6c96238c84b8f88.
- Stable run 40290851665 attempt 1, job 121465296317, completed/success 26 October 2026
  08:25:20 UTC; all job steps passed.
- APK ZIP 12636309160, 315,114,444 bytes, SHA-256
  d5989414d89a78e299614d43f224db60903242d4ffd1deeb92fd550c885dbb2a;
  expires 2 November 2026 at 08:25:13 UTC.
- Android report 12636443758, 210,254 bytes, SHA-256
  c6512e8c0e4547fe642e65b3c1244cf6b4f6401b0c2163f56175487fde2923fd.
  Downloaded/hash checked/parsed: 168 entries, 4 XMLs, 112 cases (38+38+18+18),
  zero failures/skips, plus 2 independently parsed real-credential transcripts.
- Public signer SHA-256 unchanged:
  0a4f0f2f8fa9f25b20d43c3d2c407f05fd757e4b6cd080548146e5e1ca182467.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| private-document-assistant-offline-debug.apk | 157459145 | 2dbe1bcb093b5816a0d0cb43527dddb1c6c9aea3ee6a39df74352d9eea809373 |
| private-document-assistant-connected-debug.apk | 157459193 | 67c5c64ca325a069b08572f91df57df2d48ce267fe44d2f163642156207fcbaa |

Large APK ZIP was not independently downloaded; individual APK/signature/alignment
identity is actual CI logs/gates plus GitHub artifact metadata. The small Android
report was independently downloaded and verified. No key/secret was read locally,
regenerated or committed, and no temporary signing fallback exists.

The model run 40290851638 attempt 1 failed pinned OCR dependency HTTP 404 before
Gradle/Kotlin/model download/KVM/native. Exact canonical URL independently returned
200 with the pinned 12,848,454-byte AAR and matching SHA locally; same-origin query
alternatives returned 404 and were rejected. Existing bounded retries and all pins
remain unchanged. One unchanged failed-job retry is attempt 2; no inference verdict
is inferred from the earlier failure. No application/model bytes changed in retry.

### Actual code 9 model experiment

Run 40290851638 attempt 2 passed on the nominal 4096 MiB API36 x86_64 emulator,
using the exact existing 347,251,840-byte model and pinned 0.16.0 CPU runtime.
Android reported 4,014,272 KiB total and 2,370,260 KiB available before the test,
with 4096-byte pages. Application memory admission and forced denial-before-load
passed. This is constrained-emulator evidence, not universal physical 4 GB support.

The actual gateway recalled the synthetic name in 15,760 ms (5 UTF-16 units).
Five additional native operations passed: arithmetic after greeting history,
a different color question, a source-specific answer, missing-owner abstention
and a source summary. These took 78,291 ms combined, including per-operation
verification/loading/native work, and generated 317 UTF-16 units. Exact source
contexts were checked, but this small fixture is not representative accuracy.
The document 1024/192 profile was actually exercised, not inferred from file size.

The initial chat operation took 72,145 ms including initialization. A separate
cancellation operation took 26,171 ms including its own initialization, with
89 actual native cancellation calls and one expected failing send. That total
is not cancellation-request latency. Final model hash and narrow private-file
canary scans passed; no response text was logged/stored by the probe.

Sampling captured 204 observations of synthetic PID 2692 with zero read, timeout
or metadata errors. Maximum sampled RSS was 2,685,435,904 bytes; observed kernel
VmHWM was 2,708,688,896 bytes (~2.52 GiB). These observations are not hard process
bounds, phone timing, thermal/endurance or comprehensive privacy evidence.

Native report artifact 12653230125 is 9,822 bytes,SHA-256
89cd37443cba5d762c94b82dddbda187ad73d97064d7cf77bb0bf41f0fea1bf6,
expires 2 November 2026 at 13:06:20 UTC. The small ZIP was downloaded/hash checked/parsed;
its result requires exactly one non-skipped Android test and five real semantic
cases. Attempt 1's dependency failure remains recorded; the unchanged retry cleared
that bootstrap failure with no application/model pin or byte change.

Evidence: [stable build](checkpoints/2026-10-26-chat-repair-build.json),
[local host](checkpoints/2026-10-26-chat-repair-host.json),
[desktop roles](checkpoints/2026-10-26-chat-role-desktop.json),
[native attempts](checkpoints/2026-10-26-chat-repair-native.json).

Next: phone-check repeated-greeting repair, actual suggestions, broader stored-text
questions, partial/source links and update-preservation on iQOO Neo 10 / Android 16.
Continue M5 with representative held-out retrieval/faithfulness/language/injection
quality; native process isolation/hard interruption, sustained 4 GB physical tiers,
complete traffic/privacy/thermal checks, real 16 KiB loading, recovery and M6 tools
remain open. This is a development update, not a release or completed whole M5.

## Historical checkpoint 12 — actual local chat and cited document facts

[Download stable-signed v0.5.0-dev chat APKs](https://github.com/nikslogy/private-doc-ai/actions/runs/40190793629/artifacts/12598142402).
Unzip/install the offline APK over **Private Vault Preview**, without uninstalling
or clearing data. Code8 retains the Preview IDs and signer. The already imported
compact Qwen3 model is reused after receipt/hash verification; no model reimport
or download is needed for an intact installation in the same Preview app.

Open **Assistant**, type a message and press **Send**. This executes the actual
local model with bounded recent conversation context. Generated replies are
explicitly unverified; chat is memory-only and clears on lock. There are no file
mutation tools or remote fallback. Choose a document in the source selector for
supported printed English policy/invoice questions, exact source-page quotes and
links. **Summarize document facts** gives a bounded extractive facts summary,
with conflict/partial warnings. Arbitrary narrative document analysis remains
under development. See [CHAT.md](CHAT.md) for actual capabilities and limits.

The user confirms code7's compact-model synthetic test completed on their
12 GB iQOO Neo 10 / Android16. This is a useful manual runtime confirmation,
not an independently measured phone benchmark, new-chat test or full security
suite. The user correctly reported that code7 had no actual chat input; this
build adds the missing interface and actual user-prompt gateway.

### Implemented boundaries and validation

General chat receives only the current user message and bounded recent chat
history. LocalChatGateway re-verifies the exact installed model, holds its lease
through native cleanup and rechecks memory inside the heavy-work gate. It has no
vault reader, URI, database key, action executor, network or durable transcript.
Obvious private-document questions request an explicit source; file-action
requests point to existing direct controls. Neither document nor model text
is treated as an authorization. Language/intent rules are limited English rules.

DocumentChatEngine reads explicit printed labels from the selected immutable
revision and independently validates exact page/spans. Amount meanings remain
distinct, formatting/leading zeros survive, and missing/conflicting/partial facts
cannot turn into an invented unique amount. Source changes, clear/cancel, lock,
deletion and OCR/reindex invalidate state and reject late replies/citations.
A summary is at most eight printed facts, not a model-generated interpretation.
No schema or private plaintext storage was added; originals remain immutable.

| Gate | Actual result |
| --- | --- |
| Local full host gate | PASS: 121 Python checks; 170 passing JVM XML cases (114 core + 28 per platform variant); lint, all instrumentation compilation, dependency gates and both assemblies |
| JVM execution scope | Core 114 ran freshly in the focused command and was reused UP-TO-DATE in full host; see exact local checkpoint. App JVM tasks remain NO-SOURCE |
| Stable run 40190793629 | PASS complete gate and 96 Android executions, none skipped: 38 platform +9 app controller +1 real-credential lifecycle per variant |
| App chat lifecycle | Nine cases/variant passed: exact premium citations, missing/conflict/partial abstention, clear/source-switch/lock stale-result rejection, source invalidation and no-model notices |
| Chat gateway | Five synthetic cases/variant passed: missing/retired model, verification/revocation and prompt boundaries |
| Actual native chat run 40191631336 | PASS one fresh non-skipped test; chat prompt/history, real verified-installation gateway, one-name recall, native cancellation, unchanged model hash and narrow canary checks |
| Signing/packages/manifests | PASS both pinned Preview IDs/certificate/v2 signatures/official zipalign; offline has no INTERNET; ML Kit excluded from production |
| Native static inventory | PASS: 18 64-bit libraries per APK; 16 32-bit libraries remain unverified for 16 KiB behavior; no actual 16 KiB device result |

### Real chat experiment

Run 40191631336 used the reviewed 347,251,840-byte Qwen3 INT4 model with the pinned
0.16.0 CPU runtime on a fresh API 36 x86_64 emulator with 4096 MiB nominal RAM.
Android reported 4,014,284 KiB total and 2,407,400 KiB available before testing,
with 4096-byte pages. The actual application memory policy admitted the native
operations, and a forced-denial check returned before native initialization.

The first real chat prompt with recent synthetic history generated 15 UTF-16
units in 60,364 ms, including initialization. A separate deliberately cancelled
operation took 22,475 ms including its own initialization, with 80 successful native
cancel calls and one failing synchronous send. The third operation installed the
verified public model into a fresh test-only store, verified readiness and called
LocalChatGateway with an actual follow-up question. It returned 47 UTF-16 units
in 16,418 ms, including verification/native work, and recalled the synthetic name
Riya. The test then removed only its owned temporary model slot and passed final
model-hash/canary checks. No generated response text was logged or stored.

Numeric sampling captured 113 samples of PID 2777, with zero read/timeout/metadata
errors. Maximum sampled RSS was 2,659,856,384 bytes; observed kernel VmHWM was
2,692,517,888 bytes (~2.51 GiB). These are emulator operation/process observations,
not phone latency, a cancellation-request deadline, a hard memory bound, a
universal 4 GB-phone guarantee or representative conversational/document accuracy.
One short-name recall smoke is not a language/task evaluation.

### Exact delivered identity

- Application source: e77945c991fbda8e14248b587d5be03b7547aef4.
- Built stable commit: 2a12dbb28d9532595258193e9ad89fdc52cef5bd.
- Stable run 40190793629, attempt 1, job 121161173893; final logs 25 October 2026
  12:04:43 UTC; completed/success with all steps passed.
- APK artifact 12598142402: 314,904,826 bytes, SHA-256
  b9bee50220e1460aa5cf35f5895409feed62bb74dbc65ee32fcb750c16641f95;
  expires 1 November 2026 at 12:04:40 UTC.
- Regression report 12598022520: 171,861 bytes, SHA-256
  23a1a65caf2eea706a7f2be5edc6b220f19a2011b2f229fccc69ca417f2495bd.
  Independently downloaded/hash checked/parsed: 150 entries, 4 XML suites,
  94 cases (38+38+9+9), zero failures/errors/skips; 2 credential passes are direct logs.
- Existing signer certificate SHA-256:
  0a4f0f2f8fa9f25b20d43c3d2c407f05fd757e4b6cd080548146e5e1ca182467.
- Native runner marker: 509eb4263b99b24065c1253cf246d76e617bf602;
  bootstrap-only source: 1d3af19784b39a703790a291e183dc36200665dc.
  App/core/platform/model/dependency bytes match the built application source.
- Native report 12598467848: 8,705 bytes, SHA-256
  7da86f2227f7609fa764c124eba2c58e98a35ccca511515c5046bbc12ed6022e;
  independently downloaded/hash checked/parsed; expires 1 November at 12:12:40 UTC.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| private-document-assistant-offline-debug.apk | 157354337 | dfa42fb82330ad1960bfa20991ba39cc2166516d148a197a2e097202f1ae3a62 |
| private-document-assistant-connected-debug.apk | 157354385 | acd00e0ec233451bb0a3576db4d361a9add4bce8ca55361231bf44de34baf716 |

Large APK ZIP bytes were not independently downloaded locally; individual APK
identity/signature/alignment evidence is actual CI logs plus GitHub metadata.
Small regression/native ZIPs were independently downloaded and verified. No
signing key was read locally, regenerated or committed. No temporary signing
fallback or inference weights were added to source/APKs/workflow artifacts.

Two earlier native attempts of 40190815726 failed pinned OCR dependency retrieval
before Gradle/Kotlin, model download or any Android/native test. The runner-only
fix added safe same-origin request headers/diagnostics and exact-coordinate
Gradle cache import, retaining size/hash checks before/after bounded copy and
atomic publication. The final cache restore reported MISS; the verified bootstrap
then passed and actual Kotlin/native execution completed. No cache-import success
is claimed. Seven new helper cases passed; the subsequent local full host gate
passed 128 Python checks and unchanged 170 passing JVM XML results. Stable delivery
itself used 121 Python checks. No app/core/platform code, model pin or AAR bytes
changed in this runner fix. All attempts remain in the native/bootstrap records.

Evidence: [chat build](checkpoints/2026-10-25-chat-build.json),
[chat native](checkpoints/2026-10-25-chat-native-probe.json),
[local host](checkpoints/2026-10-25-chat-local-host.json),
[runner bootstrap](checkpoints/2026-10-25-chat-runner-bootstrap.json).

Next: phone-check actual typed chat/follow-ups, source selection/citation links,
clear/cancel/lock/restart and existing vault/model preservation in code8. Continue
M5 with bounded retrieval and evaluated broader cited document synthesis; current
summaries stay extractive. Durable encrypted chat, file-operation tools, portable
backup/recovery, process isolation/hard native interruption, representative held-
out language/accuracy/injection evaluation, full traffic/privacy/thermal checks,
two physical tiers and real 16 KiB loading remain open. This is a development
preview, not a release or a claim of completed full M5.

## Historical checkpoint 11 — compact model passes a 4 GiB Android experiment

[Download the stable-signed 0.4.2-dev APKs](https://github.com/nikslogy/private-doc-ai/actions/runs/40168429140/artifacts/12589662196).
Unzip and install the offline APK over **Private Vault Preview**, without
uninstalling or clearing data. Code7 keeps the existing Preview IDs and pinned
signing identity. Android 12/API31+ is required. Earlier Test/original apps still
have separate vaults; no automatic migration or portable recovery exists.
Actual same-key physical-phone update preservation remains a separate check.

Open **Assistant → Set up on-device AI** and choose the recommended **Qwen 3
0.6B** entry. Download/import its **347,251,840-byte** file, then run **Test local
model**. The previous 1.6 GB Qwen download is a different retired model; do not
reuse it. Its existing slot/receipt remains recognized and preserved until
explicit removal. Normal document questions/summaries remain unavailable while
M5's citation/intent/evaluation layer is pending. This update fixes the model
memory path; it does not complete the whole assistant.

The user reports that code6's memory check blocked their iQOO Neo 10 with 12 GB
physical RAM/256 GB storage and Android 16. That was admission failure before
loading, not a measured native failure on the phone. Smaller Gemma models
working in Google's app are consistent with the artifact/runtime choice being
the limitation, not the phone being inherently unable to run local AI.

### Concrete changes and actual results

The new exact INT4/no-thinking Qwen3 0.6B artifact exports 1280 context rather
than the old INT8 model's 4096. LiteRT-LM is pinned to 0.16.0; the official AAR
matches Google's published SHA-256. The adapter keeps two CPU threads and a
512-token combined budget, adds a native 128-output-token bound, disables
thinking/tools/cache and allows a five-minute operation for slower phones.
Initialization/hangs still lack a hard native interruption guarantee.

The compact profile has a 3 GiB Android-reported-total/1536 MiB currently
available admission setting, 64-bit and no low-memory state. A fresh check runs
inside the shared heavy-work gate immediately before native allocation. These
are artifact-specific experimental settings, not a universal 4 GB minimum. The
old 10/8 GiB profile is retired, not applied to every model or silently weakened.
A historical-runtime receipt regression verifies recognition/preservation and
explicit removal while leaving unknown files intact.

| Gate | Actual result |
| --- | --- |
| Local scripts/check-host.sh | PASS: 109 Python, 138 passing JVM XML cases (unchanged core 82 UP-TO-DATE, 28+28 platform fresh), no failures/skips; instrumentation compilation, lint, both assemblies and exact manifest/native checks |
| Stable CI 40168429140, attempt 1, job 121094145801 | PASS complete host and 68 Android executions (33 regression+1 authenticated lifecycle per variant), none skipped |
| Signatures/packages | PASS both Preview IDs, fixed public certificate, v2 verification and official 16 KiB zipalign |
| Offline/production policy | PASS actual offline APK has no INTERNET; ML Kit excluded from both production dependency graphs |
| Static native checks | PASS: 18 64-bit libraries/APK; 16 32-bit libraries remain unverified for 16 KiB behavior |
| Real compact-model probe 40168449811 | PASS one fresh test, 0 failures/skips; actual application admission and forced denial-before-initialization both exercised |
| Model integrity/narrow file canaries | PASS host/device size/hash, unchanged post-run model and baseline/final bounded canary scans |

The real model experiment used a disposable API 36 x86_64 emulator with 4096 MiB
nominal RAM. Android reported 4,014,272 KiB total and 2,457,980 KiB available before
the test, with 4096-byte pages. It generated 50 UTF-16 units in a 54,688 ms complete
operation, including initialization. A separate cancellation operation took
21,783 ms including its own initialization; 71 native cancel calls returned and
synchronous send failed once as expected. This is not cancellation-request
latency or phone speed evidence.

Numeric sampling collected 84 samples of PID 2701 with zero read/timeout/metadata
errors. Maximum sampled RSS was 2,630,602,752 bytes; observed kernel VmHWM was
2,653,343,744 bytes (~2.47 GiB). The old model's separate 10 GiB experiment observed
~8.14 GiB. These are exact experiment observations, not a controlled performance
benchmark, a hard memory bound or proof for all 4 GB ARM phones. Both real native
operations passed the model-specific admission check on the constrained target.

### Exact delivered identity

- Source: 0d213a5537e4dc7550ac3aadc312284c4efdec32.
- Built stable commit: f5c70f231d29c4d0be1993d2630516a66d195b68.
- Native marker: dbb46b0b56e7994d934b27a9fde8097dce2a8efc; same production source.
- [Stable CI](https://github.com/nikslogy/private-doc-ai/actions/runs/40168429140)
  completed/success; final job logs 25 October 2026 06:24:34 UTC.
- APK artifact 12589662196: 314,526,647 bytes; SHA-256
  4533edaecf41d4f020c4a6e2feeedd886dcf4c96db5de5141d8ea7d094e438a0;
  expires 1 November 2026 at 06:24:32 UTC.
- Existing signer certificate SHA-256:
  0a4f0f2f8fa9f25b20d43c3d2c407f05fd757e4b6cd080548146e5e1ca182467.
- Regression report 12589632064: 142,547 bytes, SHA-256
  ad7bba06a06dbcb3a5f3d33c7e2965728396e2a2048e877db43548d87c61a8b6.
  Independently downloaded/hash checked and parsed: 96 entries, 2 XML suites,
  66 cases, 0 failures/errors/skips; 2 additional lifecycle passes are log evidence.
- [Native report 12589781782](https://github.com/nikslogy/private-doc-ai/actions/runs/40168449811/artifacts/12589781782):
  8,264 bytes, SHA-256 265e7e328c7215085d807e092356c40e3dcc99de0b7ca8e59b11538a6579dbde,
  independently downloaded/hash checked/parsed; expires 1 November at 06:22:46 UTC.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| private-document-assistant-offline-debug.apk | 157165902 | 25cd358284c6ee7c919d47bedf21ffd40fd2bff7841cf68883b04da8db4da0ef |
| private-document-assistant-connected-debug.apk | 157165954 | 8d291239f343a9450202a15376357bf676d002eb883c01923dc25215bce25b17 |

APK identities/signatures/alignment are from actual CI logs and GitHub metadata;
the large APK ZIP was not independently downloaded locally. Small regression and
native reports were independently checked. No signing key was read locally, regenerated or committed, and no temporary-signing fallback exists. JVM XML was not
uploaded by stable CI. Local XML contains 138 passing cases: 82 unchanged core
cases reused UP-TO-DATE, plus 56 freshly executed platform cases. CI task
execution and authored source counts are recorded separately.

Evidence: [build checkpoint](checkpoints/2026-10-25-compact-model-build.json),
[native checkpoint](checkpoints/2026-10-25-compact-native-probe.json),
[local host](checkpoints/2026-10-25-local-host-build.json),
[public provenance/desktop smoke](checkpoints/2026-10-25-compact-model-provenance.json),
[decision 0007](decisions/0007-low-memory-model.md), [model instructions](MODEL_SETUP.md).
The desktop native smoke observed allocator-retained memory after engine close;
the Android experiment exercised a second native operation, but sustained repeated
use and process isolation remain open. This is a real constrained-emulator result,
not verified iQOO behavior, representative document accuracy or release readiness.

Next: verify the code7 same-key phone update, compact-model import/generation,
repeat after OCR, cancel/lock/restart and record exact APK/model/device identities,
available memory and latency under DEVICE_TESTS.md. Then implement M5's bounded
revision-pinned evidence/typed intent/verified cited QA and held-out injection/
language evaluation. Four GB remains a physical-device target until measured on
representative phones. Hard native isolation, full privacy/traffic/thermal checks,
real 16 KiB loading, representative accuracy and encrypted backup/recovery remain
pending. OCR tuning remains deferred by user request.

## Historical checkpoint 10 — verified installation and real native generation probe

[Download the stable-signed 0.4.1-dev APKs](https://github.com/nikslogy/private-doc-ai/actions/runs/39819743976/artifacts/12443389300).
Both APKs passed the full host gate and **66 Android test executions, none
skipped**, in stable run 39819743976, attempt 1. This validates the new model
installer and its synthetic storage/privacy cases. A separate real-model probe
also passed generation/cancellation and narrow hash/canary checks. Phone
compatibility and document QA remain unproven. General questions/summaries
remain disabled; the experimental model test uses only a fixed synthetic prompt.

Install the offline APK as an update to **Private Vault Preview** without
uninstalling or clearing storage. Version code 6 / 0.4.1-dev requires Android
12/API31+. Connected adds the -connected version suffix. Permanent Preview IDs
and the pinned signing identity are unchanged. Earlier Test/original apps have
separate vaults; no automatic migration or portable recovery exists. Actual
same-key phone update preservation still needs its synthetic-data procedure.

| Variant | Application ID | Launcher |
| --- | --- | --- |
| Offline | com.privateassistant.app.preview | Private Vault Preview |
| Connected | com.privateassistant.app.connected.preview | Private Vault Connected Preview |

Assistant → Document facts retains literal labelled values, conflicts and exact
source-page references. Assistant → Set up on-device AI adds reviewed model
metadata, licence acknowledgement and verified file import. Consult
[MODEL_SETUP.md](MODEL_SETUP.md) before testing. Native execution passed in two
fresh explicit emulator runs; installation
alone does not make document questions available. Code6 / 0.4.1-dev tightens
the CPU memory guard and has passed local and signed CI validation. Use this
update for phone model testing; code5's older guard admitted an insufficient
memory state. OCR production behavior is unchanged, and tuning remains deferred
by user request.

## Exact delivered build and validation

- Source: f2f5c9274617111b96001d48a70c4f9d8d8a9947.
- Built commit: 44fd595dab8bbed9bd996e88e2c99746b990e079.
- [Stable run 39819743976](https://github.com/nikslogy/private-doc-ai/actions/runs/39819743976),
  attempt 1, job 119967552372; completed/success at 22 October 2026 17:15:37 UTC.
- APK artifact 12443389300: 309,771,448 bytes; expires 29 October at 17:15:28 UTC.
- ZIP SHA-256: 0d7d03715d24711f80d50328e75aba8012d6e8188db3787f40a229c20c39de63.
- Both v2 signatures match the existing public certificate SHA-256:
  0a4f0f2f8fa9f25b20d43c3d2c407f05fd757e4b6cd080548146e5e1ca182467.
- [Regression reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39819743976/artifacts/12443189699):
  116,654 bytes, ZIP SHA-256 6945abf139474c41a567c4a6796bd9f43895a384872c6a12f72f980de72beb01.
- [Machine-readable checkpoint](checkpoints/2026-10-22-model-setup-build.json).

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| private-document-assistant-offline-debug.apk | 154788418 | 99b72b755409ffbb9c92fe2907bf88ea4a6e9a00feb037b6624470c5663e1430 |
| private-document-assistant-connected-debug.apk | 154788470 | 228ffd76e3ccf558cee90dc4f5d04eb864968481131f4fbf7a6746338a8ce0fa |

APK identities/signatures are evidence from actual CI logs and GitHub metadata;
the APK ZIP was not independently downloaded locally. The regression ZIP was
independently downloaded, hash checked and parsed: 32 XML cases per variant,
zero failures/errors/skips. Two additional credential-bound lifecycle passes
are evidenced by actual instrumentation logs, giving 66 executions total.
User-owned signing secrets remain excluded local backups/repository Actions
secrets; no key was regenerated or published. No temporary signing fallback exists.

| Check | Actual delivered-source result |
| --- | --- |
| Python fixture/native/validation/Java/APK/OCR/signing checks | PASS: 97 cases |
| Source policy | PASS |
| :core:test | PASS task; 82 authored cases |
| Both platform JVM tasks | PASS tasks; 24 authored cases per variant in code6 |
| App JVM tasks | NO-SOURCE; no app JVM coverage claimed |
| All four instrumentation Kotlin compilations | PASS |
| Both app/platform lint, production dependencies, compilation and assemblies | PASS |
| Exact APK manifests/packages | PASS; offline has no INTERNET; ML Kit excluded |
| Official apksigner, zipalign and fixed certificate pin | PASS both APKs |
| Static native 16 KiB checks | PASS: 18 64-bit libraries/APK; 16 32-bit libraries unverified |
| SQLCipher3 + migration3 + Tink4 + durability2 per variant | PASS: 24 Android executions |
| OCR boundary3 + actual Tesseract6 per variant | PASS: 18 Android executions |
| Model-store10 + encrypted input-staging privacy1 per variant | PASS: 22 Android executions |
| Real-credential Keystore lifecycle1 per variant | PASS: 2 Android executions |

JVM XML was not uploaded by that workflow; exact executed JVM counts have not
been independently extracted. The authored counts above are source counts.
All 66 Android executions used an explicit API 36 x86_64 emulator, with no skips.
They do not establish the full check-device.sh suite, Compose UI races,
physical fingerprint/vendor behavior or release readiness.

## M4 implementation and model evidence

The installer accepts only reviewed public model IDs and current licence
acknowledgement. Unknown SAF input is first encrypted using Tink Streaming AEAD
with a fresh memory-only key. Only an exact expected public size/hash match
allows authenticated decryption into plaintext public weights, a second hash,
fsync and atomic publication. Incorrect private documents cannot enter plaintext
staging. The Android regression scans a same-sized synthetic private canary
while copying and after rejection. Slots/receipts live in noBackup storage;
source names/URIs and staging keys are not persisted. Scoped model leases
serialize verification/native use with removal and expire on return.

The real pinned LiteRT-LM CPU adapter configures two threads, a 512-token combined
budget, no cache/tools, bounded input and post-allocation output checks. A joined
watchdog requests native cancellation; synchronous send must return before close.
Native initialization/hangs have no hard interrupt guarantee. Lock clears UI
state and revokes work. Only fixed synthetic input reaches the diagnostic; vault
keys, document evidence and user questions do not. See ARCHITECTURE and PRIVACY.

The experimental catalogue contains only legacy Qwen 2.5 1.5B at immutable
revision 19edb84c69a0212f29a6ef17ba0d6f278b6a1614, 1,597,931,520 bytes, SHA-256
faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9.
Its exact public download was independently size/hash verified in both explicit
model probes. No inference weights are bundled or downloaded by routine checks.
Evaluated task/language lists remain empty. The pinned runtime README links the
legacy artifact; a later same-named file has different bytes/runtime metadata.
[Decision 0005](decisions/0005-local-model-candidate.md) records exact provenance
and licence sources. Gemma's checksum/licence gate remains unresolved.

| Attempt | Actual outcome |
| --- | --- |
| Stable 39759673652 | Failed app Kotlin compilation at a missing provider-stream closing brace; corrected before delivered build |
| Stable 39760351605 attempt 1 | Pinned JitPack AAR unavailable; no APK; unchanged attempt 2 passed full gate |
| Model 39759683730 | Public bytes verified; failed before load because the raw path check rejected an Android app-data alias |
| Model 39760757984 | Public bytes verified; test process crashed after start; no completed test/native metrics and cause unknown |
| Diagnostic 39762884663 attempts 1 and 2 | Same pinned JitPack AAR unavailable before platform/probe Kotlin/emulator execution; core Kotlin did execute |

The alias fix preserves canonical containment, controlled filenames, symlink
rejection, exact hashes and canary scans. Test-only phase/PID events and focused
package exit-info/crash diagnostics were used in subsequent synthetic CI runs.
Physical-target scripts do not collect these crash logs. The earlier emulator
had 4 GiB RAM; its crash remains unattributed. The final target used 10 GiB nominal
RAM and required 12 GiB measured host availability. Nominal emulator RAM differs
from Android-reported total memory. This direct runtime probe does not exercise
the phone UI's independent capability guard. Neither setting is a measured model
minimum or phone proof.

## Code6 source checks and historical next task

A bounded explicit GET bootstrap now verifies the same pinned public OCR AAR
before atomic publication in an ignored exact-coordinate local Maven cache.
It preserves the configured proxy/CA and rejects redirects, wrong size/hash and
symlinks. Only transient transport/404/429/5xx failures receive three bounded
attempts. The mandatory Gradle pre-build hash check remains unchanged. No
verified official alternate mirror was established and no dependency pin changed.
The actual local GET verified all 12,848,454 bytes and the existing SHA-256.
Twenty new synthetic tests passed, raising routine Python checks from 77 to 97.
The code5 stable run independently verified this bootstrap and passed all 97
Python checks plus its full host/66-case Android gate. Code6 repeated these
gates with the stricter memory policy and a new version; its APK bytes differ.

Pinned SDK/Gradle bootstrap now works locally. The first full local attempt
failed at the missing javac in the preinstalled runtime; the next exposed the
new JDK's missing environment CA. A published-checksum-verified Temurin JDK21
using the existing system Java trust store resolved both without disabling TLS.
The full code6 scripts/check-host.sh run then passed: 97 Python checks,
130 actual JVM cases (82 core + 24 per platform variant, no failures/skips), all
four instrumentation Kotlin compilations, lint, assemblies, production dependency
checks, manifests and native checks. The independently parsed local JVM XML and
exact environment scope are recorded in the
[local host checkpoint](checkpoints/2026-10-22-local-host-build.json).
Local APKs are diagnostic .dev builds, not the stable signed user delivery.
KVM is absent locally; no local device suite or native inference pass is claimed.

The 6 GiB nominal native probe 39814089348 compiled with the verified GET cache,
verified all model bytes on host and device, and passed its baseline canary scan.
Android then killed PID2985 for reason3 LOW_MEMORY before generation started.
Actual pre-test MemTotal was 6,072,516 KiB and MemAvailable 4,457,868 KiB. This is
an observed memory failure for this experiment, not evidence about the earlier
4 GiB crash or a phone minimum. The scoped 6,890-byte diagnostic ZIP was downloaded
and independently SHA-256 checked. A larger bounded target subsequently passed. A smaller public candidate
assessment is retained as [research only](decisions/0006-smaller-cpu-candidate.md);
no additional model was registered or tested.

The subsequent [10 GiB nominal probe](checkpoints/2026-10-22-native-model-probe.json)
39816778021 passed exactly one fresh non-skipped real generation/cancellation
test. It generated 111 UTF-16 units in a 58,576 ms full operation, including
initialization. The separate cancellation operation took 17,295 ms including
its own initialization; 204 native cancellation calls returned and synchronous
send failed once before cancellation returned. These are emulator operation
observations, not phone latency or semantic-quality claims. Model bytes/hash
were unchanged and both narrow canary scans completed. Actual pre-test guest
MemTotal was 10,188,980 KiB and MemAvailable 8,492,668 KiB. The 7,208-byte report
ZIP was independently downloaded/hash checked. Its memory sampler captured
zero samples because tr buffered the phase/PID stream; zero is absent evidence,
not a measured RSS peak. The line-buffering fix was validated and repeated in
run 39819779900, which passed one fresh real test with no skips. It generated
111 UTF-16 units in a 79,135 ms complete operation; the separate cancellation
operation took 16,700 ms, including its own initialization. Native cancellation
returned successfully 242 times and synchronous send failed once as expected.
Model hash and both narrow canary scans passed. The sampler captured 110
numeric process samples with zero read/timeout/metadata errors: observed max
RSS 8,704,966,656 bytes and kernel-reported VmHWM 8,742,727,680 bytes (~8.14 GiB).
Pre-test guest MemTotal was 10,188,992 KiB and MemAvailable 8,547,288 KiB. The
8,356-byte report was independently downloaded and SHA-256 checked. These are
x86_64 emulator process measurements, not a physical-phone RAM minimum. The
direct runtime probe bypasses the separate UI memory guard.

Code6 source adds a reviewed-artifact/runtime-only CPU policy: at least 10 GiB
Android-reported total memory and 8 GiB currently available, 64-bit and no low
memory state. Unknown profiles fail closed; stricter catalogue requirements
remain binding. The UI checks before model verification and again immediately
before native load, showing actual/required memory when denied. These are
conservative application settings for a 12 GB class phone, not measured model
minimums or a compatibility promise. Eight new policy cases passed per variant
(24 platform JVM cases each). The full code6 host run then passed all 97
Python checks, 130 actual JVM cases (82 + 24 + 24, zero failures/skips), all
instrumentation Kotlin compilation, lint, assembly and exact manifest/native
checks. The stable signed CI run passed its complete host/66-case Android gate
and published the code6 APKs. The measured-memory repeat also passed as above.

Next: run the phone import/tamper/cancel/lock/restart and same-key update
procedure in DEVICE_TESTS.md, recording exact APK/model identity and actual
memory/latency. The reported iQOO Neo 10 / Android 16 has 12 GB physical RAM
and 256 GB storage, according to the user; it has no independently measured
inference result. The high emulator memory observation warrants a cautious
synthetic phone test, not a compatibility promise. M5's next implementation is
revision-pinned cited questions/summaries with typed intent and injection
controls; user-visible QA remains gated on device and held-out evaluation.
Installation does not complete M4. M6 tools and M7 portable encrypted
backup/recovery follow.

Remaining gates include the full explicit-target device suite, two physical
phone tiers, real 16 KiB loading, physical authentication/lifecycle, hard native
isolation, comprehensive filesystem/log/traffic/memory/thermal checks, native
provenance/notices, encrypted thumbnails and remaining import validation.
Representative accuracy requires the plan's labelled held-out evaluation.
Recovery remains unavailable. No release, remote private processing, telemetry
or hidden cloud fallback was enabled.

## Earlier checkpoints

The user reported unlock/import and later OCR working on iQOO Neo 10 / Android16
and deferred OCR tuning. This manual report is not an independently verified
APK hash, same-key update preservation test or full hardware suite.

- [Document-facts build](checkpoints/2026-10-22-document-facts-build.json): code4,
  full host and 44 selected Android executions. Its OCR snapshot isolation removed
  only ProfileInstallerInitializer metadata in the test APK; production startup
  and every file/canary/hash assertion remain unchanged.
- [Stable OCR build](checkpoints/2026-10-22-stable-ocr-build.json),
  [earlier OCR build](checkpoints/2026-10-22-ocr-build.json) and
  [OCR input provenance](checkpoints/2026-10-22-ocr-input-provenance.json).
- [First-unlock repair](checkpoints/2026-10-22-first-unlock-repair.md): historical
  SQLCipher-setting repair and selected real-credential emulator evidence.
