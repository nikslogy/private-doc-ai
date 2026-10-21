# Bundled OCR is probe-only pending SDK review

Decision: 18 October 2026, development checkpoint. Keep bundled ML Kit Latin
and Devanagari recognition dependencies in **instrumentation tests only**.
Neither production flavour contains ML Kit. Both can extract native PDF and
UTF-8 text and report actual OCR unavailable.

Google's [Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
states that ML Kit collects device/application information, installation IDs
and usage/performance diagnostics. No supported public opt-out has been
verified for the selected text-recognition SDK. Bundled models alone do not
establish zero network traffic or absence of diagnostics. Do not use internal
reflection, unverified Firebase flags or library exclusions as supposed fixes.

The no-telemetry requirement concerns collection/persistence as well as network
traffic. Lack of INTERNET permission alone would not establish that SDK
diagnostic queues are absent. Therefore the SDK and its initializer are absent
from production dependencies. The synthetic-data development probe removes the
automatic initialization provider and INTERNET/network-state permissions from
its test manifest; it initializes manually only on an offline target. These
probe measures are not evidence that all SDK caches are harmless or encrypted.
Inspect actual dependency graphs, disk/log canaries and SDK metadata before
reconsidering inclusion. A permission boundary does not control other system
apps or a compromised OS.

The common pipeline accepts an OCR engine at its boundary. Production uses an
explicit unavailable capability rather than fake recognized text; the device
probe injects the actual bundled engine. Originals and already extracted native
pages remain usable when OCR cannot run. Script recognition does not establish
Hindi/Marathi conversational accuracy.

Revisit production OCR only with a verified supported diagnostics control or
a separately reviewed local OCR implementation. A future download/backup
connection must not silently enable diagnostic traffic. Actual resolved
dependency graphs, merged manifests and connected-build traffic remain release
gates; no APK/device result is claimed by this decision.
