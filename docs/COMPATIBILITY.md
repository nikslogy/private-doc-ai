# Compatibility record

As of 19 October 2026. This records pinned inputs and inspected source APIs.
It is not evidence of a successful Android build, phone run, licence audit or
security update review. See STATUS.md for commands actually executed.

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
| `io.legere:pdfiumandroid:1.0.35` | Memory PDF parsing/rendering | Tagged Kotlin APIs inspected; AAR/ABI/16 KiB checks and phone run pending |
| `com.google.ai.edge.litertlm:litertlm-android:0.9.0` | Explicit M0 CPU model load probe | Tagged APIs inspected; downloaded AAR metadata, merged minSdk, bytecode/ABIs and actual model load pending |
| `com.google.crypto.tink:tink-android:1.17.0` | Object Streaming AEAD and key envelopes | Official release exists; actual Android primitive probes remain device checks |
| `androidx.room:room-runtime`, `room-ktx`, `room-compiler:2.7.1` | Encrypted metadata schema | Official release exists; requires Kotlin 2.0+; KSP2 selected |
| `net.zetetic:sqlcipher-android:4.9.0` | SQLite encryption | Maintained package family; tagged API21+ source inspected; Room/wrong-key/native checks pending |
| `androidx.sqlite:sqlite-ktx:2.5.0` | Room SupportSQLite integration | Use `SupportOpenHelperFactory`, without `setDriver` or bundled plaintext SQLite |
| `androidx.exifinterface:exifinterface:1.4.2` | Bounded image EXIF orientation handling | Official release documented; actual Android decoder/rotation tests pending |
| `com.google.mlkit:text-recognition:16.0.1` and `text-recognition-devanagari:16.0.1` | Instrumentation probes only | Official bundled coordinates/APIs inspected; excluded from both production variants due diagnostics collection |

Do not infer LiteRT-LM's published artifact metadata from its Git tag. The
Google Maven AAR must be resolved and inspected before declaring the tuple
compatible. Never add metadata-version bypasses or `tools:overrideLibrary` to
make this table pass.

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
actual session proxy remains unreachable and downloads are not verified.

## Exact spike APIs

`MemoryDocumentParser` opens a bounded `ByteArray` with `PdfiumCore.newDocument`.
It closes document/page/text handles, extracts native page text and renders
ARGB_8888 into a bounded bitmap. It never stages a plaintext PDF file. Returned
images are recycled after a synchronous callback. The Pdfium Java logger is
disabled; native logging still needs canary inspection.
[Tagged core API](https://github.com/johngray1965/PdfiumAndroidKt/blob/v1.0.35/pdfiumandroid/src/main/java/io/legere/pdfiumandroid/PdfiumCore.kt),
[tagged page API](https://github.com/johngray1965/PdfiumAndroidKt/blob/v1.0.35/pdfiumandroid/src/main/java/io/legere/pdfiumandroid/PdfPage.kt).

The runtime probe uses `EngineConfig` with CPU, a 512-token cache budget and
`:nocache`, then `initialize`, `createConversation` with automatic tool calling
disabled, an idle `cancelProcess`, and `close`. It produces no answer. Version
0.9.0's Flow cleanup does not itself invoke native cancellation. An eventual
inference adapter must explicitly cancel native generation, await termination,
discard the conversation after cancellation, and release KV/engine state on
lock. Native initialization has no cancellation API in this pin.
[Engine](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.9.0/kotlin/java/com/google/ai/edge/litertlm/Engine.kt),
[configuration](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.9.0/kotlin/java/com/google/ai/edge/litertlm/Config.kt),
[conversation](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.9.0/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt).

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
[LiteRT-LM licence](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.9.0/LICENSE),
[SQLCipher licence](https://github.com/sqlcipher/sqlcipher-android/blob/v4.9.0/LICENSE).

`config/models.json` intentionally contains no model records. No hash, licence
acceptance, language quality or download availability is invented. Runtime
licensing does not grant model-weight rights. Complete immutable model identity,
bytes/digest, licence terms/acceptance, tokenizer/template/runtime compatibility,
language/task evaluation and resource profile before enabling a model.

Before M0 closes: resolve dependencies; compile/lint both variants; inspect
merged offline permissions and every native ELF/ZIP 16 KiB alignment; run the
encrypted-memory parser, SQLCipher and crypto tests; inspect logs/disk. Before
M4 closes: select an approved model and measure load, inference, native active
cancel, lock release and memory on required phone tiers. No such results are
claimed here.
