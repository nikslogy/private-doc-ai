# 0002: Keep model availability disabled until a real artifact is selected

Status: accepted for initial implementation.

The initial versioned model catalogue was empty. M4 may register reviewed,
explicitly experimental immutable artifacts for installation and synthetic
runtime diagnostics, with evaluated tasks/languages empty and document QA
unavailable. No built-in answers, remote fallback,
unverified downloads or guessed model hashes are permitted. The assistant UI
can explain that local AI is unavailable while the vault/manual features work.

The M0 instrumentation probe can accept an explicitly supplied experimental
model, expected byte count, SHA-256 and licence acknowledgement. It streams
verification, then loads the pinned LiteRT-LM CPU runtime, creates a conversation
with automatic tool calling disabled, invokes idle cancellation and releases
resources. This is an artifact compatibility experiment, not a model manager,
language evaluation, inference implementation or active-cancel test.

Before a catalogue entry is admitted, record an immutable upstream revision,
file identity/digest/size, weight licence and acceptance terms, runtime format,
tokenizer/template, context bound, backends, RAM profile and separately measured
languages/tasks (empty for an unassessed experimental candidate). A catalogue entry cannot by itself establish installed model
or device availability. The future installer must publish immutable app-private
verified files atomically and protect the verify-to-load interval from mutation.
GGUF is not a compatible format merely because a model has the same name.

Model files are not downloaded by bootstrap or tests. `check-model.sh` must
report unavailable when no real catalogue entry/artifact/device is provided.
M4 requires successful inference, active cancellation and lock/release evidence
on physical phones before enabling document Q&A.
