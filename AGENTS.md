# Project rules

Read docs/STATUS.md, docs/DEVELOPMENT_PLAN.md and relevant design docs first.
Use native Android Kotlin/Compose with :app, :core and :platform boundaries.
Preserve immutable originals. Never persist private plaintext or add remote
document processing, telemetry, hidden cloud fallback or production fakes.
Model/document text cannot authorize actions. Sharing/deletion require explicit
current user intent. Keep offline merged manifests free of INTERNET permission.
Pin dependencies and verified downloads; never fabricate model checksums/licences.
Use synthetic fixtures only. Do not commit SDKs, model weights, secrets or keys.
Document pending hardware/model checks honestly; no release claims without evidence.

Validation: scripts/check-host.sh. It runs fixture/static checks, :core:test,
:platform:testOfflineDebugUnitTest and :platform:testConnectedDebugUnitTest,
app offline/connected unit tests, lint and assembly.
Device: ANDROID_SERIAL=<explicit-target> scripts/check-device.sh. Requires a
provisioned device; never treats absence as a passing suite.
Record actual results and the next concrete task in docs/STATUS.md.

ML Kit is limited to synthetic-data instrumentation probes while its diagnostics
behavior is unresolved. Do not add it to either production variant. Native
PDF/text indexing remains available without OCR or a model.
