# Privacy at this checkpoint

The intended local processing boundary is implemented in source, with runtime
verification pending. Originals and sensitive metadata are encrypted under an
app-private, no-backup directory. Document names/digests do not appear in object
filenames. Import reads only user-selected SAF URIs; no broad storage permission
or durable source grant is requested. Text/PDF validation uses transient memory.
Extracted page text, source spans, index state and the full-text index persist
only inside SQLCipher. Search-query/evidence UI state clears on lock; source
quotes and input text are not logged or saved through UI state restoration.

There is no remote OCR, inference, embedding, vector database, content analytics,
crash upload, Drive client or WhatsApp relay. No AI API key is required. The
offline flavour removes INTERNET/network-state permission; both merged APKs must
still be inspected after build. Connected includes INTERNET for future explicit
downloads/backups but has no configured provider. Transitive SDK/native behavior
must be measured before privacy claims are released.

ML Kit documents diagnostic collection, including installation identifiers and
usage/performance metrics. It is absent from both production variants. Its
real OCR adapter is an instrumentation-only synthetic-data probe guarded
against INTERNET permission; production reports OCR unavailable. Blocking
network permission alone would not establish absence of local diagnostic
queues. See [the decision record](decisions/0003-probe-only-bundled-ocr.md).
The host gate checks direct source dependencies and the resolved production
runtime graph, once tooling is available, for accidental SDK inclusion.

The next development build uses a separate local Tesseract adapter with pinned
public English/Hindi/Marathi language files. Model downloads happen on the build
runner and are verified before packaging; the app does not download them.
Images are supplied as bounded memory bitmaps. The adapter disables supported
debug/output options before private pixels, creates a fresh native engine per
page and stores recognized evidence only in SQLCipher through the existing vault
transaction. A cooperative stop/deadline does not safely kill a hung native call.
Synthetic file-canary checks, actual recognition and Android compilation passed
in the stable development build. These do not establish complete native privacy,
traffic, memory or real-document accuracy. See the OCR decision and status for
actual evidence and pending device checks.

Export is a direct user action through the system file picker. The output is an
unencrypted external copy, possibly in a cloud provider selected by the user.
The provider or recipient may retain it. Failed/cancelled exports attempt to
delete partial output; provider refusal is reported. Vault deletion leaves
source provider files and exported copies unchanged. No forensic-erasure claim
is made. System document providers may themselves use a network to retrieve
selected files, including when this app lacks INTERNET permission.

The vault requires configured system authentication. App screens request Android
secure-window protection. Manual/screen-off lock revokes the application session;
background timeout is 15 seconds. Provider/native calls may take time to return.
Memory clearing is best effort and cannot guarantee zeroization of JVM/native
copies. A compromised OS/process or malicious update can defeat protections.

Automatic Android backup and device transfer are excluded in the manifest and
both rule formats. **Recovery is not implemented.** Uninstall, device loss or
Keystore invalidation can permanently lose vault access. Retain original sources
and use synthetic data during development. No support-side recovery key exists.

Before release: inspect actual merged manifests, device files/journals/logs and
connected-build traffic with synthetic canaries; verify screen/authentication
lifecycle, native memory/cache behavior, backup exclusions and completed recovery.
Authored tests and source checks do not establish those outcomes.
