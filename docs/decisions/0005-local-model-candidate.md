# 0005: Prepare verified local-model installation before enabling generation

Research checkpoint: 22 October 2026, 05:29 UTC. This is a candidate assessment,
not an approved catalogue entry or evidence of successful model execution.
No model weights were downloaded, no model-provider credentials were requested or used, and
no model load, generation, language/QA, cancellation or device benchmark was run.
At that initial research checkpoint config/models.json was empty. Subsequent M4
source registers only the reviewed legacy Qwen artifact below as experimental;
it does not enable document answers or summaries. Actual execution results
are recorded separately in STATUS.

## Candidate for the pinned runtime

The official Google AI Edge Gallery
[allowlist at commit c99568266df59c8f82d63dda77feb5957713953f](https://github.com/google-ai-edge/gallery/blob/c99568266df59c8f82d63dda77feb5957713953f/model_allowlists/1_0_14.json)
(Git blob 27befc3b6868410fda7a070334aff42ee9604ffb) records:

| Property | Upstream value |
| --- | --- |
| Model repository | litert-community/Gemma3-1B-IT |
| Model revision | 42d538a932e8d5b12e6b3b455f5572560bd60b2c |
| File | gemma3-1b-it-int4.litertlm |
| Bytes | 584417280 |
| Upstream minimum device memory guidance | 6 GB |
| Upstream listed accelerators | GPU, CPU |

The [LiteRT-LM 0.9.0 Kotlin guide](https://github.com/google-ai-edge/LiteRT-LM/blob/991ef53d47fe7be53c1411120f29936f9f94602d/docs/api/kotlin/getting_started.md)
uses the Gemma3-1B-IT family in its example. This supports selecting it for a
first compatibility experiment with the currently pinned runtime. It does not
establish that the exact file above loads, generates correctly or meets the
project's privacy/resource requirements on this phone.

The allowlist supplies a byte count and immutable model revision, but no SHA-256.
The [Gemma repository](https://huggingface.co/litert-community/Gemma3-1B-IT/tree/main)
permits public file listing and states that accessing model files requires a
logged-in Hugging Face user to acknowledge the Gemma licence. Attempts to read
the model file/pointer metadata without credentials returned HTTP 401. No gate
was bypassed. An authoritative content SHA-256 was **not obtained** and must
not be invented or inferred from a Git commit or Xet hash. Do not register or
enable this candidate until the expected digest, exact artifact metadata and
applicable licence requirements have been reviewed.

The weight licence is Gemma, separate from LiteRT-LM's Apache-2.0 runtime licence.
The [Gemma Terms of Use](https://ai.google.dev/gemma/terms), as read at this
checkpoint, include acceptance, redistribution notice/agreement and use-policy
requirements. Review them for the actual installation/distribution path and
record explicit acknowledgement before model use. The research did not accept
terms on the user's behalf or establish redistribution clearance.

## Public legacy Qwen artifact is a candidate for the pinned runtime

Follow-up research on 22 October 2026 corrects the initial assessment below:
the current model card's runtime requirement applies to a later metadata update,
not automatically to every historical artifact with the same filename.

The same immutable Gallery allowlist records Qwen2.5-1.5B-Instruct:

| Property | Upstream value |
| --- | --- |
| Model repository | litert-community/Qwen2.5-1.5B-Instruct |
| Model revision | 19edb84c69a0212f29a6ef17ba0d6f278b6a1614 |
| File | Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm |
| Bytes | 1597931520 |
| SHA-256, from the upstream Git LFS pointer | faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9 |
| Upstream minimum device memory guidance | 6 GB |

The [immutable file metadata](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/blob/19edb84c69a0212f29a6ef17ba0d6f278b6a1614/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm)
publishes that SHA-256. The [upload commit](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/commit/19edb84c69a0212f29a6ef17ba0d6f278b6a1614),
dated 25 November 2025, exposes the exact Git LFS pointer size and digest in its
diff. These establish authoritative upstream metadata, not a hash of bytes
independently downloaded and verified by this project. No weights were fetched.

The [model card at the same immutable revision](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/blob/19edb84c69a0212f29a6ef17ba0d6f278b6a1614/README.md)
declares Apache-2.0 and contains no 0.18.0 minimum. The upstream base model's
[licence at its introduction commit](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct/blob/2fd50615a2a9792d223eba8e0741aa90ef21a869/LICENSE)
is Apache-2.0, with Alibaba Cloud's copyright notice. The app can require a
licence acknowledgement as an installation control without claiming that
Apache-2.0 imposes a Hugging Face account/acceptance gate. Review notices and the
actual distribution path before redistributing model weights.

The [pinned LiteRT-LM 0.9.0 README](https://github.com/google-ai-edge/LiteRT-LM/blob/991ef53d47fe7be53c1411120f29936f9f94602d/README.md)
lists Qwen2.5-1.5B as a chat-ready, 8-bit, 4096-context model and links this exact
legacy filename. This is upstream compatibility guidance for the first real
experiment; it does not establish successful loading/generation by this app.

The reason the current card differs is visible in
[later commit 5e6e7a58d03777898b31330a15d2e0a5a9107de3](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/commit/5e6e7a58d03777898b31330a15d2e0a5a9107de3),
whose parent is the selected revision. That commit changes the embedded metadata
and chat template for LiteRT-LM 0.18.0 while reporting unchanged weights. It
replaces the selected legacy file with **different bytes**:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Selected legacy revision 19edb84 | 1597931520 | faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9 |
| Later metadata revision 5e6e7a5 | 1597922856 | c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e |

Consequently, never use a moving main download URL or accept the later digest
as a substitute. The legacy artifact may be recorded for explicit experimental
installation and synthetic runtime diagnostics on the existing 0.9.0 pin,
with evaluated languages/tasks empty and normal document QA still disabled.
Actual byte verification, engine load, synthetic generation, active cancellation,
privacy/resource tests and phone capability measurements are required. No
runtime upgrade or model execution is part of this research checkpoint.

## Runtime API and cancellation requirements

The current 0.9.0 tag resolves to source commit
991ef53d47fe7be53c1411120f29936f9f94602d. Its relevant APIs are:

- [Engine](https://github.com/google-ai-edge/LiteRT-LM/blob/991ef53d47fe7be53c1411120f29936f9f94602d/kotlin/java/com/google/ai/edge/litertlm/Engine.kt):
  background `initialize()`, `createConversation()` and `close()`. The pin has
  no engine-initialization cancellation entry point.
- [Configuration](https://github.com/google-ai-edge/LiteRT-LM/blob/991ef53d47fe7be53c1411120f29936f9f94602d/kotlin/java/com/google/ai/edge/litertlm/Config.kt):
  `maxNumTokens` bounds the combined input/output KV context; `cacheDir =
  ":nocache"` disables caching. Conversation configuration defaults automatic
  tool execution to true, so the application must explicitly set
  `automaticToolCalling = false` and provide no mutation tools to document QA.
- [Conversation](https://github.com/google-ai-edge/LiteRT-LM/blob/991ef53d47fe7be53c1411120f29936f9f94602d/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt):
  real `sendMessage` and callback/Flow `sendMessageAsync`; `cancelProcess()`
  requests native cancellation. The Flow implementation's `awaitClose {}` does
  not cancel native work. Source notes that cancellation does not roll back
  conversation state.

The adapter must bridge explicit native cancellation to coroutine/user
cancellation, wait for synchronous native send to return and join its watchdog before closing resources,
discard cancelled conversations, and clear session/KV/engine state on vault
lock. Use one heavy job at a time, bounded excerpts/input/output and no private
text logs or persistent chat/cache. Active cancellation latency and native
initialization/hang isolation remain unverified. A load probe's idle cancellation
call does not satisfy them.

## Next concrete M4 work

Implement a model-installation state machine and SAF import into app-private
no-backup storage: bounded staging, expected size/hash verification, explicit
licence acknowledgement, durable atomic publication, tamper/cancel cleanup and
verification before load. Keep unsupported/unregistered models unavailable for
normal document answers. This can be implemented and tested with tiny synthetic
byte fixtures while the curated catalogue remains empty; it does not require a
download, account secret or remote AI service.

Then add the real pinned-runtime inference/cancel/release adapter behind that
verified installation boundary, with generation disabled until a reviewed
artifact is available and actual synthetic-data load/inference/privacy tests
pass. Offline installation uses the user's selected local model file and adds
no INTERNET permission. Any later connected download needs an explicit separate
user action and the same artifact checks.

The reported phone is an iQOO Neo 10 running Android 16; its RAM, available
memory, ABI/backend behavior, thermals and inference performance have not been
measured here. Read actual device capability rather than assuming it from the
model name. Gemma's 6 GB Gallery guidance is not this project's tested minimum.
No English/Hindi/Marathi conversational or document-QA accuracy is claimed.
