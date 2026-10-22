# Selected-source labelled facts (M3 development slice)

The Assistant tab offers direct inspection without an AI model. The user must
choose one document; its document/revision IDs stay pinned while switching tabs
or opening a source page. No policy is chosen from import order, and dates do not
establish a current policy. Free-form questions and summaries remain unavailable.

`core.facts.LabelledFieldExtractor` reads a small, explicit English label:value
grammar from stored page text. It distinguishes premium, pre-tax premium, tax,
insured value, sum insured and deductible. Generic owner/total/subtotal labels do
not establish a policy-holder or invoice classification. It retains raw values,
leading-zero identifiers and date formatting, uses exact decimal parsing for
supported monetary formats, and does not calculate missing values or infer
currency from an ambiguous symbol. Unsupported wording/layouts are omitted.

Every candidate carries the complete original text line and exact immutable
revision/page/UTF-16 span. EvidenceValidator checks it in core and again before
the UI publishes results. Different raw values for one field remain conflicting;
the application never silently chooses one. Quotes show extracted text, which
can contain OCR errors or misleading statements. A citation proves the text is
present, not that its claim is true. Document text has no action authority.

Limits are 50 pages, 1 million UTF-16 code units per page, 2 million overall,
512 per line and 256 candidates. Bounds that prevent inspecting all input produce
a visible partial-results warning. Incomplete/failed/unread pages also produce a
warning, including retained evidence after an unsuccessful OCR retry. Absence
of a candidate never establishes that the original lacks that fact.

The controller checks the selected revision before returning page evidence.
Facts are computed on a worker dispatcher and retained only in unlocked UI
memory. Session and generation checks reject late results after a source change
or lock. Lock clears the selected source/results; deletion and explicit reindex
invalidate facts for that document. Extraction changes neither database schema
nor immutable original objects. Page text remains inside SQLCipher at rest.

Synthetic core tests cover amounts, grouping, leading zeros, ambiguous dates,
conflicts, injection-like prose, Unicode offsets, revisions and resource bounds.
The real authenticated vault lifecycle probe checks pinned reads, wrong revision
and locked rejection, cited/conflicting fields, reopen consistency and original
ciphertext/byte preservation. See STATUS for actual execution results. UI race,
phone-layout and representative policy accuracy evaluation remain separate gates.

This slice advances M3; it does not complete model-based retrieval or QA. M4 is
verified local model management and actual LiteRT-LM generation/cancellation;
M5 adds evaluated cited questions and summaries. No remote processing path was
added.
