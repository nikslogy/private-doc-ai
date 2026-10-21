# Private Document Assistant

Native Android development checkpoint for a local document vault and future
on-device document assistant. **This is source code, not a verified release.**
No APK has been built in this environment: Android SDK/Gradle downloads could
not run through its network-access gate. See [STATUS](docs/STATUS.md) for exact
checks, blockers and the next task.

The current app source connects system authentication to a Tink-encrypted object
vault and Room/SQLCipher metadata. It provides document-picker import, native
PDF/text extraction, encrypted local content search, a page-text evidence viewer,
original export, explicit deletion and automatic locking. Questions, production
OCR, summaries, conversions, recovery and backups are unavailable. There are no
demo answers or cloud inference calls.

## Build

Linux setup requires Bash 4+, Python 3.10+, curl and a JDK 17 or newer.
The inspected host has JDK 21; output bytecode targets Java 17. Pinned versions
are in [config/toolchain.json](config/toolchain.json) and
[COMPATIBILITY](docs/COMPATIBILITY.md).

```bash
# Review and accept the Android SDK terms before using the acceptance option.
scripts/bootstrap-android.sh --accept-sdk-licenses
source .tooling/env.sh
scripts/check-host.sh
```

Bootstrap checks disk/RAM, verifies pinned Gradle, wrapper and command-line-tools
SHA-256 values, installs official SDK packages, and writes local environment
configuration. Existing verified archives can be placed in `.tooling/downloads`
for restricted environments. The official wrapper JAR is installed by bootstrap;
it is not included in this source checkpoint. The repository `gradlew` launcher
also supports the pinned bootstrap-installed distribution. Do not substitute
an unverified binary or bypass the proxy/TLS policy. SDK platform-tools is an
official rolling package; see the compatibility record for that pin limitation.

Bootstrap and `gradlew` share `scripts/java-tool-env.sh`: Java receives the
inherited credential-free proxy route, and Gradle/Android user caches default
to ignored writable `.tooling/` paths. Inherited JVM trust/options are preserved,
not written into generated environment files. Unsupported authenticated proxy
configuration fails with an explanation instead of exposing credentials.

Expected output after a successful build:

- `app/build/outputs/apk/offline/debug/app-offline-debug.apk`
- `app/build/outputs/apk/connected/debug/app-connected-debug.apk`

These are debug builds with development signing, not app-store packages.
The offline overlay removes INTERNET and network-state permissions; the
artifact checker inspects the actual merged APK. Connected currently has no
configured service. Dependency network behavior and native compatibility still
require device/traffic checks.

To build, verify and stage both downloadable APKs after setup:

```bash
python3 scripts/stage-apks.py
```

It runs the full host gate, then verifies the exact staged APKs with the manifest/
native checker, official `apksigner` and `zipalign`. Successful output includes
both APKs, checksums and package/signing metadata in `artifacts/apk/`. The CI
workflow uploads that directory after success for seven days. No CI build has
been run or APK produced from this workspace yet. See [APK delivery](docs/APK_DELIVERY.md).

## Validation

Run the checks that need only Python without downloading Android tools:

```bash
python3 scripts/test-fixtures.py
python3 scripts/test-validation.py
python3 scripts/test-native-libraries.py
python3 scripts/test-apk-delivery.py
python3 scripts/security-check.py
```

`check-host.sh` additionally runs pure Kotlin tests, both platform/app flavours'
unit tests and lint, production dependency inventory, both app assemblies,
merged APK checks, and native ELF/ZIP alignment checks. It fails when prerequisites are
missing; passing the Python checks alone does not establish Android correctness.

```bash
ANDROID_SERIAL=<explicit-device-serial> scripts/check-device.sh
```

Device procedures are in [DEVICE_TESTS](docs/DEVICE_TESTS.md). Real Tink,
SQLCipher/migration/search, Keystore-policy, encrypted-memory PDF/image,
development OCR and activity tests are authored.
An empty [model catalogue](config/models.json) deliberately prevents an invented
model-load result. No model weights or AI API credentials are needed for host CI.

## Supported development boundaries

- PDF, JPEG, PNG and UTF-8 text import; 25 MiB/file, 50 PDF pages,
  20 megapixels/source image. Password-protected PDFs are rejected explicitly.
- Native PDF/text page evidence, source spans, extractor versions and the FTS
  index live inside SQLCipher. Search returns a quote from a specific revision
  and page. It is literal lexical matching, not an answer or policy-validity judgment.
- PDF/text validation runs in bounded input memory. Images get framing/PNG CRC,
  bounded sampled pixel decode and EXIF orientation handling. These checks do
  not establish parser isolation or successful decoding of every possible image.
- Production OCR is unavailable because ML Kit documents diagnostics collection.
  Its real adapter is test-only; neither production flavour depends on that SDK.
  See the [decision record](docs/decisions/0003-probe-only-bundled-ocr.md).
- No plaintext vault originals or metadata are intentionally written to disk.
  Authorized exports are ordinary external files; recipients/providers can keep
  them. Runtime disk/log leakage has not been checked on a device.
- Screen-off/manual lock is immediate at the session boundary; background lock
  timeout is 15 seconds. Blocking provider/native calls cancel cooperatively.
- No recovery yet. Uninstalling or losing the Keystore key can lose vault access.
  Use synthetic test files and retain their sources while developing.

## Project map

| Path | Responsibility |
| --- | --- |
| `app/` | Compose UI, system authentication, picker and lifecycle |
| `core/` | Immutable references, evidence/money validation, typed actions and authorization |
| `platform/` | Encrypted vault, Android adapters, bounded parser and real runtime probe |
| `test-fixtures/` | Deterministic synthetic insurance documents and separate ground truth |
| `scripts/` | Bootstrap, fixtures, host/device/model/artifact checks |
| `docs/` | Requirements reference, design, compatibility and progress evidence |

Start subsequent work with [STATUS](docs/STATUS.md), then the
[development reference](docs/DEVELOPMENT_PLAN.md). The original temporary plan
attachment disappeared when the environment restarted; that file transparently
records a condensed transcription of the requirements already read.

To package a reviewable source checkpoint without SDKs, weights or build caches:

```bash
python3 scripts/package-source.py
```

The deterministic ZIP includes a source-file checksum manifest. A source archive
and its checksums do not imply Android compilation or device verification.
