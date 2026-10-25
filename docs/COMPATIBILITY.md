# Compatibility record

As of 25 October 2026. Current source pins LiteRT-LM 0.16.0 for an experimental
compact Qwen 3 INT4 CPU profile. Production platform Kotlin compilation passed;
its full host gates and actual 4 GiB Android generation/cancellation probe remain
pending. This is not a tested minimum or a physical-phone compatibility claim.

The earlier delivered code6 / 0.4.1-dev Preview APKs used LiteRT-LM 0.9.0 and
passed full host checks and 66 Android executions in
[run 39819743976](https://github.com/nikslogy/private-doc-ai/actions/runs/39819743976),
source f2f5c9274617111b96001d48a70c4f9d8d8a9947. Those APK signers match the fixed
public development certificate; IDs end in .preview. Separate legacy-model
generation/cancellation and process-memory evidence is preserved in the
[historical native checkpoint](checkpoints/2026-10-22-native-model-probe.json).
It does not validate the new runtime/model. Exact build identities and
physical/privacy/provenance limits are in STATUS.md. Earlier repair evidence:
both variants compiled, passed lint and produced verified
debug APKs in [CI](https://github.com/nikslogy/private-doc-ai/actions/runs/39715674766)
from source 850a87567dba8cc686539419110ca0eae7d5baad. Host/static and selected Android 16 emulator regression evidence passed;
physical phone loading, licence audit and security update review remain pending.
See STATUS.md for commands actually executed.

## Toolchain

| Input | Pin |
| --- | --- |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| KSP | 2.2.21-2.0.4 |
| Build JVM | JDK 21 |
| Application Kotlin/Java bytecode | 17 |
| compileSdk / targetSdk / minSdk | 36 / 35 / 31 |
| Android build tools | 35.0.0 |

The original proposed Kotlin 2.1.20/AGP 8.9.2/SDK 35 tuple was changed to consume
the selected Pdfium binding coherently. Its tagged build uses Kotlin 2.2.21,
compileSdk 36, minSdk 24 and bytecode 17. AGP's published requirements support
Gradle 8.13 and JDK 17 or newer. The project uses JDK 21 to run Gradle.
[Pdfium build](https://github.com/johngray1965/PdfiumAndroidKt/blob/v1.0.35/pdfiumandroid/build.gradle.kts),
[Pdfium Kotlin versions](https://github.com/johngray1965/PdfiumAndroidKt/blob/v1.0.35/gradle/libs.versions.toml),
[AGP requirements](https://developer.android.com/build/releases/agp-8-13-0-release-notes).

## Native and persistence dependencies

| Dependency | Purpose | Verified source / remaining evidence |
| --- | --- | --- |
| `io.legere:pdfiumandroid:1.0.35` | Memory PDF parsing/rendering | Published artifact resolved/compiled; packaged ABI/64-bit static 16 KiB checks passed; phone parsing pending |
| `com.google.ai.edge.litertlm:litertlm-android:0.16.0` | M0 probe and compact experimental M4 CPU adapter | Official Maven AAR byte count/SHA-256 independently checked against the published checksum; production platform Kotlin compilation passed with the existing toolchain; new full host/native alignment and actual model execution pending |
| `com.google.crypto.tink:tink-android:1.17.0` | Object Streaming AEAD and key envelopes | Official release exists; actual Android primitive probes remain device checks |
| `androidx.room:room-runtime`, `room-ktx`, `room-compiler` and `androidx.room` plugin `2.7.2` | Encrypted metadata schema | Official release; Kotlin 2.0+/KSP2; plugin isolates per-variant schema outputs and fixes empty schema directory configuration |
| `net.zetetic:sqlcipher-android:4.9.0` | SQLite encryption | Maintained package family; tagged API21+ source inspected; Room/encryption/wrong-key probes passed on API36 x86_64; physical checks pending |
| `androidx.sqlite:sqlite-ktx:2.5.0` | Room SupportSQLite integration | Use `SupportOpenHelperFactory`, without `setDriver` or bundled plaintext SQLite |
| `androidx.exifinterface:exifinterface:1.4.2` | Bounded image EXIF orientation handling | Official release documented; actual Android decoder/rotation tests pending |
| `com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.9.0@aar` | Local bitmap OCR | Exact 12,848,454-byte AAR SHA-256 pinned; no transitive dependencies; actual synthetic recognition passed on API36 x86_64; physical quality/privacy pending |
| `com.google.mlkit:text-recognition:16.0.1` and `text-recognition-devanagari:16.0.1` | Instrumentation probes only | Official bundled coordinates/APIs inspected; excluded from both production variants due diagnostics collection |

The new runtime's official AAR is 20,192,711 bytes, SHA-256
`420e3dbef46f93d182529438aa2f0091654a5b124a547966d2ef4b0045330d89`.
The locally resolved bytes match Google's actual
[published SHA-256](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.16.0/litertlm-android-0.16.0.aar.sha256).
Its POM declares Apache-2.0 and exact Gson 2.13.2, Kotlin reflect 2.2.21 and
coroutines-android 1.9.0 dependencies; the project's existing coroutines 1.10.2
pin remains. The AAR contains arm64-v8a and x86_64 JNI libraries. On 25 October,
`:platform:compileOfflineDebugKotlin` passed in 57 seconds using the documented
JDK21/toolchain and existing system Java trust store. No TLS, Kotlin metadata or
manifest compatibility checks were bypassed. This compilation is not native
execution, 16 KiB alignment/loading evidence or a complete transitive audit.

Earlier pinned artifacts resolved and compiled in CI without metadata-version
bypasses or `tools:overrideLibrary`; the new runtime's full gates remain pending.
Source tags alone are not published artifact metadata evidence. Full resolved
AAR/POM provenance and native source/licence mapping remain required; host
compilation does not establish runtime compatibility.

Both app and platform now have offline/connected flavours; use flavour-qualified
platform test/lint/instrumentation tasks. `:app:verifyProductionDependencies`
inventories both production runtime graphs and rejects ML Kit inclusion. Its
real SDK probe is test-only with manual initialization and an INTERNET-denied
guard; this is not a supported diagnostic opt-out. ML Kit has its own SDK terms,
not an inferred Apache model licence. See [the OCR decision](decisions/0003-probe-only-bundled-ocr.md),
[ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure),
[bundled OCR guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android),
and [ExifInterface releases](https://developer.android.com/jetpack/androidx/releases/exifinterface).

`verify-artifact.sh` now runs a bounded native ABI/ELF/ZIP inventory. Synthetic
parser tests are not evidence about the actual Maven/native artifacts. See
[NATIVE_COMPATIBILITY.md](NATIVE_COMPATIBILITY.md) for the exact 16 KiB checks
and remaining device/notice/provenance evidence.

Sources: [Tink 1.17 release](https://github.com/tink-crypto/tink-java/releases/tag/v1.17.0),
[Room releases](https://developer.android.com/jetpack/androidx/releases/room),
[SQLCipher 4.9 source](https://github.com/sqlcipher/sqlcipher-android/tree/v4.9.0),
[SQLCipher migration](https://www.zetetic.net/sqlcipher/sqlcipher-for-android-migration/).

The 19 October build audit corrected nine Tink keyset API calls to
`readWithAssociatedData` / `writeWithAssociatedData`, as declared in the
[pinned KeysetHandle source](https://github.com/tink-crypto/tink-java/blob/v1.17.0/src/main/java/com/google/crypto/tink/KeysetHandle.java).
The intended AAD/envelope format and dependency versions remain unchanged.
This source correction does not establish successful compilation.

`scripts/java-tool-env.sh` now maps the inherited credential-free proxy routing
to Java system properties for the wrapper, Gradle and SDK manager. It preserves
inherited JVM trust/options and uses ignored writable `.tooling/` defaults for
Gradle and Android user caches. It never copies proxy credentials into JVM
arguments or the generated environment file. Unsupported authenticated proxy
URLs or unrepresentable NO_PROXY settings stop setup with a safe error rather
than silently changing routing. Tests use synthetic hosts/fake tools; the
initial session proxy was unreachable. Current network access now permits the
verified SDK/Gradle bootstrap and exact OCR AAR GET. A complete Temurin JDK21
was downloaded at an immutable release URL and checked against its published
GitHub asset digest; it uses the existing system Java trust store for this
environment's CA. No certificate verification was disabled. Current full local
host results and distinct CI/model checks are recorded in STATUS.

## Exact spike APIs

The next OCR build uses a separately reviewed Tesseract adapter, not the ML Kit
probe. `config/ocr-runtime.json` pins the resolved AAR; `config/ocr-models.json`
pins public English/Hindi/Marathi tessdata_fast bytes at immutable revision
87416418657359cb625c412a48b6e1d6d41c29bd. The app receives bitmap pixels in
memory and checks supported debug/output options before recognition. The
interruptible binding entry point is getHOCRText; transient markup is discarded
and evidence is constructed from ResultIterator lines. Watchdog stop requests
settle before native cleanup/bitmap recycling; no hard native timeout is claimed.
The [actual input inventory](checkpoints/2026-10-22-ocr-input-provenance.json)
records 16 KiB alignment for OCR arm64/x86_64 libraries and 4 KiB for 32-bit ABIs.
Published sources JAR was unavailable; the wrapper tag was reviewed but a
reproducible native binary/source identity is not established.

`MemoryDocumentParser` opens a bounded `ByteArray` with `PdfiumCore.newDocument`.
It closes document/page/text handles, extracts native page text and renders
ARGB_8888 into a bounded bitmap. It never stages a plaintext PDF file. Returned
images are recycled after a synchronous callback. The Pdfium Java logger is
disabled; native logging still needs canary inspection.
[Tagged core API](https://github.com/johngray1965/PdfiumAndroidKt/blob/v1.0.35/pdfiumandroid/src/main/java/io/legere/pdfiumandroid/PdfiumCore.kt),
[tagged page API](https://github.com/johngray1965/PdfiumAndroidKt/blob/v1.0.35/pdfiumandroid/src/main/java/io/legere/pdfiumandroid/PdfPage.kt).

The selected 0.16.0 tag resolves to immutable source commit
`924e79c91542761242244e4f1651851f822e4cbb`. Its retained `Engine.initialize`,
`createConversation`, synchronous `sendMessage`, `cancelProcess` and `close`
APIs support the existing ownership/cancellation adapter. The current source
configures `Backend.CPU(threadCount = 2)`, a 512-token combined context,
`:nocache`, no tools, automatic tool calling disabled and deterministic sampling.
`ConversationConfig.maxOutputToken = 128` adds a native output-token bound and
`ThinkingConfig(enableThinking = false)` requests non-reasoning generation.
These are inspected API/configuration facts, not executed model results or a
claim that the exported model's KV allocation shrinks to the configured context.

The M0 probe creates/closes an idle conversation and does not produce an answer.
The M4 adapter uses synchronous native send and a joined watchdog that explicitly
requests cancellation, waits for send to return, then closes conversation/engine.
The configured operation deadline is at most five minutes for slower CPUs; it
cannot forcibly interrupt native initialization or a native hang. A fresh
admission callback runs inside the OCR/render/model heavy-work gate immediately
before native engine allocation. The UI still supplies only a fixed synthetic
prompt. No model/document text receives action authority. Actual full-build,
memory, privacy, cancellation and phone results are tracked separately in STATUS.
[Engine](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/kotlin/java/com/google/ai/edge/litertlm/Engine.kt),
[configuration](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/kotlin/java/com/google/ai/edge/litertlm/Config.kt),
[conversation](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt).

The earlier 0.9.0 Flow cleanup did not invoke native cancellation itself; its
adapter therefore used the same synchronous-send/joined-watchdog strategy.
Historical successful legacy-model probes remain evidence only for that exact
runtime/model/target, not compatibility with 0.16.0 or the compact model.

No GPU/NPU compatibility is asserted. CPU also needs an actual compatible
model and physical-device memory/load/quality tests. The M0 idle-cancel call
does not establish active cancellation latency or successful inference.

## Licences and release gates

PdfiumAndroidKt's wrapper is Apache-2.0 and includes PDFium's BSD-style notice.
LiteRT-LM, Tink and AndroidX use Apache-2.0. SQLCipher Community has its own
BSD-style terms. Review and ship full notices for all resolved native/transitive
components, including their crypto/PDFium dependencies, before distribution.
This is an initial inventory, not a complete notice bundle.
[Pdfium licence](https://github.com/johngray1965/PdfiumAndroidKt/blob/v1.0.35/LICENSE),
[LiteRT-LM licence](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/LICENSE),
[SQLCipher licence](https://github.com/sqlcipher/sqlcipher-android/blob/v4.9.0/LICENSE).

`config/models.json` registers the compact experimental Qwen 3 0.6B INT4
no-thinking artifact for 0.16.0 with exact immutable size/hash/licence sources.
Its 347,251,840-byte download identity is separate from runtime compatibility;
actual generation/cancellation and memory behavior on the planned 4 GiB Android
target remain pending. The older Qwen 2.5 1.5B record retains its 0.9.0 identity
to preserve recognition/removal of existing model installations, but is not an
executable profile under the new runtime. Its prior verified-byte and successful
native-probe evidence remains historical. See decision 0005 for the legacy
artifact and the different later file with the same name. Evaluated tasks and
languages remain empty for both, and installation does not enable document QA.
Runtime licensing does not grant model-weight rights. Actual language/task
evaluation and a measured resource profile remain required before QA.

The historical host dependency/compile/lint/merged-manifest/native/signature/
alignment gates passed for both code6 APKs; the new runtime must repeat them.
32-bit 16 KiB behavior remains unverified. Before M0 closes,
run the encrypted-memory parser, SQLCipher and crypto tests on devices, inspect
logs/disk, verify runtime 16 KiB behavior and finish provenance/notices. Before
M4 closes: select an approved model and measure load, inference, native active
cancel, lock release and memory on required phone tiers. The new compact profile
has no physical-phone or completed 4 GiB generation result recorded here yet.
