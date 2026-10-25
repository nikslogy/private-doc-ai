# Local chat and document facts — first M5 slice

Code8 / 0.5.0-dev replaces the Assistant tab's unavailable screen with a chat
composer. Use the same compact model installed for code7; its catalogue and
receipt identity are unchanged. The setup screen still offers its diagnostic,
but normal conversation starts in **Assistant**, with your own message and Send.
Consult STATUS for actual build/native results before choosing a download.

## General chat

Keep the source set to **General chat**. The real on-device Qwen3 model answers
your message and can use recent conversation context. A verified compact model
installation is required. Replies are labelled **Local AI · unverified** and may
be wrong; there is no claim of representative language or document accuracy.
The model cannot inspect the vault, manipulate files, share, delete or call tools.
Obvious questions about an unselected private policy/invoice ask for a source;
obvious file-action requests point to existing direct controls instead of
claiming that an operation completed. These bounded English routing rules are
not an evaluated general-language intent classifier.

Messages are limited to 400 UTF-16 units, with a 1024-unit total prompt. Up to four
recent turns contribute at most 160 units each; older/long content is excluded
or marked as an excerpt. Visible history is bounded to 20 messages. Replies have
a native 128-token bound and a 2048-unit post-allocation text check. The current
512-token context is deliberately small for memory use; some languages may hit
the token budget with shorter text. Operations allow up to five minutes, but
native initialization/hangs still have no hard interruption guarantee.

## Ask a selected document

Choose **Ask a document**, then one imported source. This starts a fresh chat;
source selection does not follow import order or an inferred current policy.
Try **What is the policy number?**, **What is the total premium?** or
**Summarize document facts**. Replies contain supported printed English facts
and source-page buttons. Original formatting, leading zeros and exact decimal
text are retained. OCR can still have misread the original; review source pages.

Supported fields include policy number, vehicle registration, policyholder,
owner, printed coverage dates, total/pre-tax premium, tax, insured value, sum
insured, deductible, invoice number/date/total/subtotal and generic labelled
total/subtotal. A bare amount or premium asks for clarification. Missing labels,
conflicts and incomplete reading do not produce invented unique answers. Dates
remain printed text rather than proof a policy is valid or current.

A summary is at most eight distinct printed facts with exact citations. It is
labelled partial when evidence or the returned facts are truncated, and conflicts
remain visible. This first slice does not provide arbitrary narrative document
analysis, comparison, arithmetic, language-general extraction or model-based
semantic summarization. No supported fact answer depends on an LLM inventing or
correcting a value. Questions outside the conservative grammar explain the limit.

## Session and validation boundary

Clear chat, source changes and lock discard chat/history; messages are not
restored after locking or restarting. Cancellation revokes publication even if
native processing is still returning. Deletion and reindex/OCR invalidate source
messages and citations, including changes under the same original revision ID.
Nothing is sent to a server and the offline APK has no INTERNET permission.
JVM/native copies cannot be perfectly zeroized, and native memory may outlive
UI clearing while cleanup finishes. Durable encrypted chat history is future work.

Host tests cover prompt bounds, field meaning/integrity and intent rules. Selected
Android regressions cover nine actual controller lifecycle cases and five gateway
availability/cancellation cases. The explicit real-model probe exercises actual
chat prompts with short history, a verified installation through LocalChatGateway,
one synthetic name recall, active cancellation and bounded hash/file-canary checks.
Consult STATUS/checkpoints for the actual completed/failed/unrun verdicts. This is
functional development evidence, not the 120-question release evaluation, a full
Compose tapping/keyboard test, representative multilingual quality, comprehensive
privacy, physical-phone performance or release readiness.
