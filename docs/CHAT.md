# Local chat and document questions

Code9 / 0.5.1-dev repairs the code8 conversation format and expands document
questions beyond a small set of printed policy/invoice fields. Consult STATUS
for actual validation and APK availability. The exact compact model and receipt
are unchanged; an intact existing installation can be reused.

## Personal identifiers and file tools (code 10 / 0.6.0-dev)

The Assistant now opens in **My documents** scope once the vault has documents.
Before any model call, `AssistantCommandParser` reads only the user's own typed
message with deterministic English rules. It recognizes:

- **Identifier lookups** such as “What is my PAN number?”, “my Aadhaar no”,
  passport, voter ID (EPIC), driving licence, GSTIN, IFSC, vehicle registration,
  bank account, UAN, email, mobile and date of birth. `PersonalIdentifierExtractor`
  scans bounded stored page text (at most 16 sources / 200,000 UTF-16 units per
  request, name matches first) with strict patterns: PAN holder-type letter, Aadhaar
  Verhoeff checksum and consistent grouping, document-label gates for passport/
  voter/licence/IFSC/vehicle numbers and same/previous-line labels for account,
  UAN, mobile and date of birth. Each value is shown exactly as printed with its
  cited page; masked Aadhaar is labelled masked. Several different values are all
  shown. No value is generated, and no model is needed. Unread scans are reported
  instead of claiming absence.
- **Find a document**: “show my PAN card”, “open my passport”.
- **Compress to a size**: “this PDF under 200 KB”, “compress my resume to 1.5 MB”.
  KB/MB are decimal (200 KB = 200,000 bytes), which also satisfies forms that count
  1,024-byte KB. Results are measured encoder output; an unreachable target reports
  the smallest measured size instead of producing an oversized file.
- **Convert**: “my PAN in image format” (PDF pages → JPEG, up to 10 pages; PNG →
  JPEG), “convert this photo to PDF” (A4 page).
- **Passport-size photo**: 35 × 45 mm (413 × 531 px at 300 DPI) or US 2 × 2 in,
  cropped around the face found by Android's on-device `FaceDetector`. Background
  and lighting are not altered; without a face the crop is centred and says so.

A tool request becomes an app-owned card naming the resolved document, the
operation and (for PDF compression) the disclosure that page text, links, forms
and signatures are not preserved. Nothing runs until the user taps **Create
copy**. Ambiguous matches show a document choice first. Outputs are stored as new
encrypted documents in the source's folder with a parent link; the original is
unchanged. Result cards offer **Open** and **Save to phone** (system picker).
Clear, lock and source removal discard pending cards. Rules are English-only and
limited; anything else falls through to the existing chat routes unchanged.

## General chat

Choose **General chat**, type a message, then Send. System instructions, recent
user/assistant pairs and the current question are separate native messages.
User text that resembles a role delimiter remains user text. Recent history is
bounded to two complete pairs, with excerpts at160 UTF-16 units per turn; the
current question remains complete up to400 units. General input totals at most
1024 units, with512 native context tokens and128 output tokens. UTF-16 length
is not token count; some scripts can exhaust context earlier.

Generated replies are labelled unverified. The small local model can give wrong
answers. General chat has no vault reader or file tools. Select a document or
**All documents** to supply source evidence. Obvious file-action requests point
to the existing direct controls; chat cannot share, delete or modify originals.

## Document questions

Choose one document for a specific source, or **All documents** to search a
bounded authorized scope. Ask a normal question about readable content, such as
**What does it say about cancellation?**, or **Summarise it** for an overview.
Relevant exact passages enter the same local model in a separate document
request. Its generated answer is displayed with numbered source excerpts and
page links. Matching references and printed numbers are checked, but this does
not prove the model interpreted a passage correctly. Check the displayed quotes.
Invalid references or unsupported numbers fall back to real source excerpts.
Model absence, memory denial or generation failure also shows retrieved source
text with an explanation. A source with no readable text needs indexing/OCR.

Recognized printed-field questions still take the direct exact-value path in a
selected document: policy number, premium, tax, insured value, dates and invoice
fields retain original formatting and citations. Missing/conflicting/incomplete
facts cannot become an invented unique amount. A bare premium/amount asks for
clarification. These English label rules are limited and OCR may misread values.

Retrieval retains at most16 source revisions and200,000 UTF-16 units, with at
most50 pages per source. For larger libraries, bounded queries to the existing
encrypted lexical index prioritize matching documents before source reads.
At most four480-unit source excerpts enter one model request. The document
profile uses at most3072 input units,1024 native context tokens and192 output
tokens, within the artifact's1280-token export. This remains a small context.
Summaries sample passages across sources/pages and state when they are partial;
they are not a guarantee every page or document was analyzed. Questions with no
useful retrieved evidence abstain. Selecting a specific document reaches sources
that a bounded all-documents pass omitted. Lexical retrieval and multilingual
semantic accuracy remain development limitations.

## Session and source authority

Chat and retrieved plaintext live only in unlocked-session memory. No saved UI
state, plaintext transcript, native KV cache or remote processing is added.
Clear, lock, source changes, deletion and reindex/OCR revoke pending work and
remove stale messages/citations. All-documents changes invalidate its scope.
General history excludes document answers and notices. Source buttons are owned
by the app and revalidate current document/revision/page authorization; generated
text cannot create a navigation callback or authorize a file operation.

Native cancellation is cooperative; initialization/hangs still lack a hard
interruption guarantee. JVM/native copies cannot be perfectly zeroized. The
same compact model is reused, but phone memory/performance and repeated use need
actual measurement. This development update is not a release or a claim of
complete document comprehension. STATUS records actual tests and remaining work.
