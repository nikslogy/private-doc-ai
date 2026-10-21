# Development status

Checkpoint 4: 21 October 2026 — authorized GitHub publishing and APK build.
**No APK has been produced. Source is still uncompiled and device-unverified.**
The earlier implementation/evidence checkpoint is preserved in
[checkpoints/2026-10-18.md](checkpoints/2026-10-18.md).

## Current blocker and repository

The user supplied `https://github.com/nikslogy/private-doc-ai` for the APK build.
GitHub integration now reads the repository successfully and reports push/admin
permissions. The contents API confirmed the repository is empty on 21 October.
The prepared source/workflow is being published through the authorized GitHub
connection; CI results and APK checksums will be recorded after the build.
Do not request installation again or overwrite work added after this inspection.

Locally, JDK 21 is available but the pinned Gradle distribution/wrapper and
Android SDK are absent. Default downloads fail at `proxy:8080`. A narrowly
scoped network-enabled wrapper download was interrupted while awaiting access;
no file was downloaded and no permission approval is assumed. There is no
pending download to wait on. Do not repeat the long permission wait or bypass
the configured proxy. A working authorized build runner is required for an APK.

The remote-build route is prepared in `.github/workflows/host-checks.yml`:
build and stage both development APKs, then upload their verified delivery for
seven days. It will trigger on the source push. A successful build is still
required before any APK can be supplied. No credentials should be pasted into chat.

## Changes made

- Corrected nine invalid Tink keyset API calls to the actual pinned 1.17.0
  `readWithAssociatedData` / `writeWithAssociatedData` methods. Four production
  calls and five instrumentation calls changed. The purpose-bound AAD,
  intended envelope format and dependency versions are unchanged. Exact tagged
  API source was inspected; this is not a successful compilation result.
- Added shared Java proxy configuration for Gradle wrapper, installed Gradle
  and SDK manager. Curl proxy variables alone do not configure Java networking.
  Credential-free configured routes become validated JVM properties; existing
  trust/header options stay in memory. Proxy credentials and unsupported route
  shapes fail safely instead of being printed or silently ignored.
- Default Gradle/Android user caches now use ignored writable `.tooling/`
  directories. Bootstrap and wrapper consistently honor `JAVA_HOME`.
- Added `scripts/stage-apks.py`. It always runs the full host gate and verifies
  the exact copied APKs with manifest/native checks, official `apksigner` and
  `zipalign`, expected IDs/versions/SDKs, compiled manifest/DEX presence and
  SHA-256. Both APKs publish together only on success. No skip-gate option.
- CI now runs staging with JDK 21 and uploads APKs/checksums/public reports only
  after success, using a pinned official upload-artifact action. No signing keys,
  models, SDKs, vaults or customer documents enter that delivery.
- Added nine synthetic APK delivery tests and ten Java environment tests to
  the host check. Test stubs cannot be mistaken for an actual Android build.

## Checks actually executed

The real command `python3 scripts/stage-apks.py` was run in this workspace.
Its output is retained in `artifacts/apk-build-attempt.txt`; the structured
record is `artifacts/checkpoint-checks.json`.

| Check | Actual result |
| --- | --- |
| Fixture integrity | PASS: 6 Python tests |
| Native ELF/ZIP parser | PASS: 22 synthetic Python tests |
| Validation gates | PASS: 10 mocked-tool Python tests |
| Java proxy/tool-home setup | PASS: 10 synthetic/fake-tool Python tests; no downloads |
| APK delivery gates | PASS: 9 mocked-SDK/synthetic-ZIP Python tests; no real APK |
| Source policy checks | PASS |
| Python compilation, Bash syntax, configuration/XML/resource checks | PASS |
| Real APK staging attempt | EXIT 1: host prerequisites absent after 57 passing Python tests; no APK delivery published |

**55 Kotlin domain tests remain authored and unrun.** Android dependency
resolution, production dependency inventory, Kotlin/Room compilation, lint,
APK assembly/signature/actual native checks, instrumentation and physical-device
privacy/lifecycle/performance checks remain unrun. No actual API/model/phone
result has been inferred from mocks or source review. No workflow was run by CI.

## Product and milestone state

M0/M1 remain open. Implemented source includes the encrypted immutable-original
vault, system unlock, local native PDF/text evidence and literal content search,
page-text viewer, explicit export/delete, lock/cancellation boundaries and
schema-1→2 migration. These behaviors still require a successful build and
device evidence. The prior checkpoint contains full M0–M11 status.

No product feature was added in this build-focused checkpoint. Production OCR,
AI questions/summaries, derivative tools, portable backup and recovery remain
unavailable. ML Kit stays instrumentation-only under the no-telemetry rule.
Do not weaken privacy/native/lint checks merely to produce an APK.

## Expected APK delivery after a successful build

`artifacts/apk/` will contain both actual debug APKs, `SHA256SUMS`,
`APK_MANIFEST.json` and host/package/signature/alignment/dependency reports.
Currently that directory does not exist, because staging did not succeed.
See [APK_DELIVERY.md](APK_DELIVERY.md) for names, commands and limitations.

These are development test builds, requiring Android 12/API31+. Host gates do
not establish device readiness or an audited release. Debug signing identities
can differ by builder. Uninstalling or losing the Keystore can lose vault
access; retain sources and use synthetic files until device/recovery gates close.

## Next concrete action

1. Publish the prepared source/workflow to the verified empty repository
   `nikslogy/private-doc-ai`, then inspect the automatically triggered CI run.
   GitHub access is already authorized; do not repeat the installation blocker.
2. Resolve actual dependency/compiler/lint/native/signature failures without
   suppressing gates. Run `python3 scripts/stage-apks.py` and provide the real
   APK files/checksums once it succeeds. Never rename a source archive to APK.
3. Run the explicit-target device suites and collect non-skipped migration,
   original-byte, authentication, lock, search/delete, parser, leakage and
   native/16 KiB evidence. A device-less run is not a pass.

No app-store publication, deployment, Drive enablement or WhatsApp bot is
authorized at this checkpoint.
