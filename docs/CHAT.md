# Local chat and document questions

Code9 / 0.5.1-dev repairs the code8 conversation format and expands document
questions beyond a small set of printed policy/invoice fields. Consult STATUS
for actual validation and APK availability. The exact compact model and receipt
are unchanged; an intact existing installation can be reused.

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
