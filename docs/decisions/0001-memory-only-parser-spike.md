# 0001: Bounded memory for the PDF compatibility spike

Status: accepted for M0 implementation; hard native resource isolation unresolved.

The vault gives a worker one document's decrypted bytes, never its root key.
The parser consumes at most 25 MiB, 50 PDF pages, 100,000 UTF-16 characters per
page and 1,000,000 overall. Source image headers must describe at most 20 million
pixels. PDF previews contain at most four million ARGB_8888 pixels and are
recycled synchronously. One semaphore serializes PDF and model probe work.

Use PdfiumAndroidKt's ByteArray memory-open path. Do not create a parser cache,
seekable plaintext temporary file or pipe workaround. Empty PDF text pages and
image imports are explicitly marked as needing OCR; OCR is not implemented by
the spike. Image header probing does not establish full payload validity,
truncation handling or EXIF orientation. UTF-8 decoding is strict and bounded
before String allocation.

A 15-second deadline and interrupts are checked between PDF native operations
and before a completed render/text result is accepted.
It cannot stop an individual hung native call, cap native decompression/font
allocations or contain a native crash. A 25 MiB memory-open also entails native
copies and parser-owned allocations; it is not a 25 MiB process memory bound.
This limitation blocks production hardening claims. Evaluate a restricted
isolated worker process with a killable deadline and measured memory before
accepting hostile-document workloads for release. No plaintext disk exception
has been approved by this decision.

The instrumentation probe encrypts a synthetic PDF with actual Tink Streaming
AEAD, reads bounded plaintext back into memory, performs native text extraction
and rendering, verifies the original digest, closes handles and clears its input
arrays. Execution and full disk/log canary inspection remain device gates.
