# 0005: Prepare verified local-model installation before enabling generation

Research checkpoint: 22 October 2026, 05:29 UTC. This is a candidate assessment,
not an approved catalogue entry or evidence of successful model execution.
No model weights were downloaded, no model-provider credentials were requested or used, and
no model load, generation, language/QA, cancellation or device benchmark was run.
`config/models.json` remains empty. The current APK continues to report AI
answers and summaries unavailable.

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

## Public alternative requires a separate runtime change

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
and its [small Git LFS pointer](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/raw/19edb84c69a0212f29a6ef17ba0d6f278b6a1614/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm)
were readable without credentials. These establish upstream metadata, not a
hash of model bytes independently downloaded and verified by this project.
The model page marks the licence Apache-2.0. Its
[current model card](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/blob/main/README.md),
as read on 22 October, explicitly requires LiteRT-LM 0.18.0 or newer for this
`.litertlm` file. It is therefore not a compatible drop-in candidate for the
0.9.0 pin. A runtime upgrade, dependency/native audit and actual load/inference
tests would be a separate change. No upgrade is part of this checkpoint.

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

The future adapter must bridge explicit native cancellation to coroutine/user
cancellation, await the native terminal callback before closing resources,
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
