# 0007: Qualify a small INT4 model for the low-memory path

Research checkpoint: 25 October 2026. The user reports that code6's CPU memory
guard rejects their iQOO Neo 10 with 12 GB physical RAM. They want a useful
assistant on 4 GB phones and accept slower replies. A compatibility target is
not a measured support claim; Android keeps part of physical RAM for the OS,
other apps and drivers.

The previous legacy Qwen 2.5 1.5B experiment used a 1,597,931,520-byte INT8 file.
Its successful x86_64 Android run observed process VmHWM 8,742,727,680 bytes.
That observation justified guarding that particular experiment, but not a
device-wide ban on smaller models. Slower decoding alone does not eliminate
model weights, native workspace, exported graph shapes or KV memory. Serialized
file size also is not a process-memory bound. The exact contribution of each
allocation to that earlier peak has not been measured.

## Selected public candidate

The [conversion repository](https://huggingface.co/litert-community/Qwen3-0.6B-int4/tree/256f33043931947c173d7f3cc1d84ee7a8538cc2)
offers a smaller Qwen3 model with an explicit no-thinking prompt format:

| Property | Verified value |
| --- | --- |
| Repository | litert-community/Qwen3-0.6B-int4 |
| Immutable revision | 256f33043931947c173d7f3cc1d84ee7a8538cc2 |
| File | qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm |
| Byte count | 347251840 |
| SHA-256 | 2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139 |
| Weight quantization | dynamic_int4_block32 |
| Activations/KV | FP32 |
| Exported KV context | 1280 tokens |
| Prefill signatures | 8, 64, 128, 256, 512, 1024 |
| Upstream exercised runtime | LiteRT-LM 0.16.0 |
| Account gate observed | None |

The immutable Hugging Face tree supplied the size and LFS SHA-256. This project
then downloaded all 347,251,840 bytes to ignored scratch storage and independently
matched that SHA-256. Neither inference weights nor SDK/runtime binaries belong
in the source repository or build artifacts.

The pinned [model card](https://huggingface.co/litert-community/Qwen3-0.6B-int4/blob/256f33043931947c173d7f3cc1d84ee7a8538cc2/README.md)
describes a conversion of Qwen/Qwen3-0.6B with no additional training. It documents
the conversion recipe and reports CPU and OpenCL measurements using LiteRT-LM
0.16.0 on a Galaxy S26. Its no-thinking variant reports process peaks around
1,032–1,036 MB on CPU and 761–770 MB on GPU. These are upstream observations,
not this project's measurements, a 4 GB-phone result, an app integration result
or a guarantee for other chipsets. The full card has SHA-256
d91c6889bbbae253d9367a659fa57dba3f252b1aef38bb093eb3bb6dfbdf0e2c.

The card declares Apache-2.0 inherited from Qwen3. The actual immutable
[base-model licence](https://huggingface.co/Qwen/Qwen3-0.6B/blob/c1899de289a04d12100db370d81485cdf75e47ca/LICENSE)
was retrieved: 11,343 bytes, SHA-256
832dd9e00a68dd83b3c3fb9f5588dad7dcf337a0db50f7d9483f310cd292e92e.
This is actual licence provenance, not a substitute for the application's full
dependency/notices review. Gemma account/licence restrictions are not bypassed.

## Runtime and memory choices

The earlier 0.9.0 runtime has no established compatibility with this conversion.
Use a pinned runtime that the upstream card actually exercised. Official
[LiteRT-LM 0.16.0](https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.16.0)
resolves to source 924e79c91542761242244e4f1651851f822e4cbb.
Its [Kotlin configuration](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/kotlin/java/com/google/ai/edge/litertlm/Config.kt)
retains the existing engine/conversation APIs and adds a direct output-token
limit and thinking configuration. Its native cancel API remains explicit;
Flow collection alone still does not cancel native processing.

The concrete approach is to combine a smaller INT4 artifact, a short combined
context, a separate output-token budget, two CPU threads, no reasoning-token
generation, one heavy job at a time, and model-specific admission checks.
The no-thinking file embeds a closed empty thinking block in the assistant
prefix. Multiple exported prefill lengths let short inputs use a smaller
prefill signature; the runtime selects a compatible signature. None of this
establishes an allocation bound without measurements.

For document QA, retrieval should select short, revision-pinned evidence before
generation, rather than place whole documents or the vault into the prompt.
Literal amounts/identifiers and citations need separate verification. Chunking
and short outputs trade speed/memory against context and quality; they cannot
make an arbitrarily large model fit every phone. This M5 path still needs real
held-out quality and prompt-injection evaluation before becoming a supported
document-answering feature.

Keep encrypted unknown-input staging, exact-size/hash import, verified model
leases, no-backup placement and offline manifests unchanged. Avoid plaintext
private-document caches, speculative cloud fallback and a generic override that
loads the old heavy artifact without its guard. A rejected model must be able
to suggest a smaller reviewed profile without claiming that total RAM alone
proves compatibility.

## Actual execution and remaining gates

A fresh Linux x86_64 C API process completed real CPU generation from only the
fixed synthetic blue-triangle prompt. It returned 76 UTF-16 units of nonempty
text, no tool calls and no stderr, then closed the conversation and engine.
The measured operation took 23,044 ms to initialize and 15,844 ms to generate;
the complete operation took 39,068 ms. Linux getrusage(RUSAGE_SELF) reported
2,687,024 KiB maximum RSS, or 2,751,512,576 bytes (~2.56 GiB). These are
uncontrolled desktop observations; the smaller upstream ARM result cannot be
substituted for them. No Android or 4 GB limit was simulated, and no generation
quality or cancellation claim follows.

That C child never hashes or reads a whole model/archive into an application
buffer: independent size/hash verification occurred in other processes. A
second fresh C process also passed with 76 UTF-16 units and no stderr/tool calls.
Numeric snapshots from that repeat distinguish live native allocations from
historical process memory:

| Observation | Linux process KiB |
| --- | ---: |
| Baseline VmRSS / VmHWM before engine creation | 89800 / 89800 |
| After initialization VmRSS / VmHWM | 2683740 / 2683740 |
| After generation VmRSS / VmHWM | 2687332 / 2687332 |
| After engine/conversation close VmRSS / VmHWM | 2261600 / 2687252 |
| getrusage maximum RSS | 2687252 |

The repeat took 31,558 ms to initialize, 11,675 ms to generate and 43,555 ms for
the complete operation. `/proc` snapshots and getrusage differ slightly; the
individual readings are recorded rather than claiming a hard bound or identical
accounting. Around 2.16 GiB remained resident after close in this Linux process.
Deleting engine handles therefore did not establish immediate return of native
allocator pages to the OS; process exit returned the process resources. Android
and ARM behavior still require measurement, especially a second engine created
for the cancellation test. No private data entered either smoke and no complete
privacy or native-memory erasure claim is made.

The settings were two CPU threads, combined context 512, output limit 96,
:nocache, no tools, disabled thinking, and TOP_P sampling with k=1, p=1 and
temperature 0. An initial harness attempted the C API's Greedy and then TopK
sampler types; both are unimplemented in that CPU path. Source inspection showed
that Kotlin JNI instead selects TOP_P while applying its topK field, so the
corrected harness matches that wire configuration. These harness errors do not
establish a model compatibility failure.

The official runtime archive was independently downloaded and SHA-256 checked:
[litert_lm_c_api-0.1.0.zip from release 0.16.0](https://github.com/google-ai-edge/LiteRT-LM/releases/download/v0.16.0/litert_lm_c_api-0.1.0.zip),
161,599,098 bytes, SHA-256
f0f3ae7b5730af783d1f018f7ad9a8de20c25fedf01af4e35fc11d4382246f7d.
Its own included headers and linux_x86_64/liblitert-lm.so supplied the C API;
the separately verified public model hash remained unchanged. The smoke's output
text was parsed only in memory and not retained in the measurement record.

Android generation/cancellation, hash/canary checks, process-memory sampling on
an explicit constrained emulator, native loading, and the user's physical-phone
test remain separate gates. Four GB is a product target until those tests run;
no minimum-RAM, phone latency, quality, complete privacy or release claim follows
from an upstream card or a desktop load alone. Evaluated tasks/languages must
remain empty until this project's real held-out evidence supports them.

The earlier [SmolLM2-135M assessment](0006-smaller-cpu-candidate.md) remains a
small-runtime diagnostic fallback candidate, not an established quality
substitute. SmolLM2-360M has a public 373,719,040-byte conversion, but neither
Smol candidate has been run by this project. Qwen2.5-0.5B's reviewed community
repository supplies older .task/.tflite files rather than a directly usable
.litertlm artifact for this adapter.

## Subsequent constrained Android result

The project's [real compact-model probe](../checkpoints/2026-10-25-compact-native-probe.json)
40168449811 then passed exactly one fresh synthetic generation/cancellation test
on a nominal 4096 MiB API 36 x86_64 emulator, including the actual model-specific
admission check for both operations and a forced denial-before-initialization
assertion. Android reported 4,014,272 KiB total and 2,457,980 KiB available before
the test. The 84 numeric process samples had zero read/timeout/metadata errors;
observed VmHWM was 2,653,343,744 bytes (~2.47 GiB). Model hashes and narrow canary
checks passed. This establishes that constrained emulator execution, not a
universal 4 GB-phone guarantee or English/Hindi/Marathi document-QA quality.
The code7 stable signed build separately passed 109 Python/fullhost and 68 Android
executions; physical-phone verification and sustained-use/isolation remain open.
