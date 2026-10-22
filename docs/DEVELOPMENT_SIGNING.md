# One-time development APK signing setup

OCR passed the full host gate and 44 Android test executions in run 39720899685.
Its temporary debug signer differs from the installed repair's signer. Keep
the installed apps and their data; do not uninstall or reset them.

A persistent development signing identity has now been generated in the private
workspace. Only its public certificate is committed in
[development-signing.json](../config/development-signing.json). Private files are
excluded from Git/source packages and are never put in CI caches or artifacts.

In [repository Actions secrets](https://github.com/nikslogy/private-doc-ai/settings/secrets/actions),
create two repository secrets with these exact names:

| Secret name | Value |
| --- | --- |
| ANDROID_DEV_KEYSTORE_BASE64 | Full contents of the privately provided ANDROID_DEV_KEYSTORE_BASE64.txt |
| ANDROID_DEV_STORE_PASSWORD | Full contents of the privately provided ANDROID_DEV_STORE_PASSWORD.txt |

Keep your own protected backup of both private files. Enter them directly in
GitHub Settings; do not paste them in chat, issues, source files or commits.
Once both are saved, say **Done**. Codex can trigger the prepared main-branch
delivery workflow. You can also run **Stable development APKs** manually from
the repository's Actions tab using main.

The workflow requires both secrets and the exact pinned certificate. It creates
a temporary owner-only PKCS12 file, verifies its public identity with keytool,
builds/checks both APKs, verifies their actual APK signers, runs the non-skipped
Android regressions, then publishes APKs only on success. Key cleanup runs even
on failure. There is no temporary-key fallback for stable delivery.

The permanent preview IDs are com.privateassistant.app.preview and
com.privateassistant.app.connected.preview. Launcher names are **Private Vault
Preview** and **Private Vault Connected Preview**. They install once alongside
the existing apps with separate vaults. Subsequent builds retain these IDs and
key. Old app vaults cannot be opened by a new app ID. Moving originals would be
an explicit user export/import operation; portable vault recovery is unavailable.

The host workflow additionally builds preview mode with a disposable synthetic
key, checks IDs/labels, and proves the delivery pin rejects that real but wrong
signer. Those synthetic APKs are never uploaded. An actual stable-key build and
update-preserves-vault check still require the two repository secrets and device
execution; source checks or a keytool probe do not establish those results.
