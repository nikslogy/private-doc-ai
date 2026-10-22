# 0006: Retain a smaller public candidate for a later CPU experiment

Research checkpoint: 22 October 2026. This assessment does not register a model
or establish compatibility, memory requirements, language quality or document QA.
The current Qwen candidate separately completed an explicit synthetic native
probe; its conservative phone guard and pending physical checks remain in STATUS.

The official [SmolLM2-135M conversion repository](https://huggingface.co/litert-community/SmolLM2-135M-Instruct/tree/8111e0a65fda719f0a6855e8e1a8ec8c3f9ccb22)
publishes the following immutable file metadata:

| Property | Upstream value |
| --- | --- |
| Revision | 8111e0a65fda719f0a6855e8e1a8ec8c3f9ccb22 |
| File | SmolLM2_135M_Instruct.litertlm |
| Bytes | 142819328 |
| SHA-256 from Git LFS metadata | ccdc5c85735743f081b7d44ca309cab569f76c0f2f0e8e163449a63721969c37 |
| Conversion/base model card licence | Apache-2.0 |

[Upload commit 749fc23](https://huggingface.co/litert-community/SmolLM2-135M-Instruct/commit/749fc23bc8d7ef89db031d075004289c88f41c2f)
is dated 21 May 2026. The June README update leaves the artifact unchanged.
The [conversion card](https://huggingface.co/litert-community/SmolLM2-135M-Instruct/blob/8111e0a65fda719f0a6855e8e1a8ec8c3f9ccb22/README.md)
and [base card](https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct/blob/12fd25f77366fa6b3b4b768ec3050bf629380bac/README.md)
declare Apache-2.0; no account gate was observed. This is card/licence provenance,
not a completed redistribution-notice review or permission to substitute hashes.

The conversion card gives generic Android/LiteRT-LM instructions without a pinned
minimum runtime. An HTTP206 bounded first 64 KiB header inspection found LITERTLM,
tf_lite_prefill_decode and chat-token prefixes. That does not establish loading
on the project's pinned 0.9.0 runtime. No full weight file was downloaded for this
assessment, and no model load/generation/cancel or quality benchmark was run.

If a smaller CPU experiment becomes necessary, review the exact artifact/runtime
metadata and notices, then verify all downloaded bytes and run the same real
synthetic generation/native-cancel/hash/canary/resource harness. Keep evaluated
languages/tasks empty until actual held-out evidence exists. Small file size
alone proves neither memory use nor answer quality. The production catalogue
and normal document-QA gate are unchanged by this note.
