# Local Tesseract OCR development integration

Decision: 22 October 2026. Select Tesseract4Android 4.9.0 standard
(single-threaded) for bounded offline OCR development. ML Kit remains confined
to synthetic instrumentation probes under decision 0003; neither production
variant may add it. This decision does not close device, leakage, accuracy or
release gates.

## Reviewed inputs

The [upstream 4.9.0 tag](https://github.com/adaptech-cz/Tesseract4Android/tree/15c534717b1cb58261b58d4e4c1200c7f81f668c)
resolved to 15c534717b1cb58261b58d4e4c1200c7f81f668c. Its README identifies
Tesseract 5.5.1, Leptonica 1.85.0, libjpeg v9f and libpng 1.6.48. The
library's runtime dependency declaration contains AndroidX annotation 1.3.0;
its manifest is empty. The wrapper is Apache-2.0, while native components
retain their own licenses. Actual upstream license texts are bundled under
platform/src/main/assets/ocr/licenses, with an OCR-specific NOTICE.

The [official tessdata_fast revision](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd)
is 87416418657359cb625c412a48b6e1d6d41c29bd. Its README explicitly licenses
all data Apache-2.0 and requires the LSTM engine. Use OEM_LSTM_ONLY, with
English, Hindi and Marathi files verified by size and SHA-256 before use.
Those language names identify models, not established recognition quality.
The source tree reports 4,113,088 bytes for eng.traineddata, 1,122,751 for
hin.traineddata and 2,118,233 for mar.traineddata. Real downloaded-byte hashes
and AAR verification are recorded in
[the CI provenance record](../checkpoints/2026-10-22-ocr-input-provenance.json)
and the build-input catalogue; no hash is inferred from a filename or Git SHA.

The published AAR comes from JitPack, as documented by upstream. Its verified
digest fixes the downloaded binary, but the available source review does not
prove a reproducible relationship between that binary and this source commit.
The missing published sources JAR and remaining provenance limits are recorded
in the CI input evidence. Do not describe this as a reproducible native build.

## Private data boundary

The [wrapper's Bitmap API](https://github.com/adaptech-cz/Tesseract4Android/blob/15c534717b1cb58261b58d4e4c1200c7f81f668c/tesseract4android/src/main/java/com/googlecode/tesseract/android/TessBaseAPI.java)
copies image data through an in-memory Pix; use this route exclusively. Never
call file-input, image-writing, training, renderer or output-file APIs with
private documents. Only public, verified model files may be persisted outside
the encrypted vault. Recognized text and coordinates enter the existing
encrypted evidence/search store; originals remain immutable.

Tesseract's upstream tprintf implementation defaults debug_file to the empty
string, which sends diagnostics to stderr. Initialize with debug_file=/dev/null
and debug output disabled, and verify accepted settings before private input.
Do not pass document-controlled configuration or paths. The upstream CMake
build defines GRAPHICS_DISABLED; neither debug images nor interactive viewer
output is part of this integration. Empty manifest and lack of an analytics
dependency support the selection; they alone do not prove absence of native
logging or disk writes. Synthetic private-file and log canaries, dependency
inspection and connected traffic checks remain required evidence.

Use one heavy OCR task at a time and do not share a TessBaseAPI concurrently.
The wrapper explicitly documents that getUTF8Text alone cannot be stopped;
recognize through the monitored getHOCRText path first, discard its markup,
then read bounded result-iterator text and boxes. Propagate cancellation to
stop(), bound time, pages, decoded pixels, output characters and boxes, and
revoke the vault session immediately on lock and settle native work before
recycling its image/state. Clear/recycle recognition
and adaptive state between jobs. Native/JVM heap zeroization is not promised.
Missing models or engine errors preserve original and existing native text.

## Compatibility and remaining gates

The upstream build uses NDK 27.2.12479018 with
ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON. The maintainer's
[issue 79 explanation](https://github.com/adaptech-cz/Tesseract4Android/issues/79)
states that 16 KiB support targets 64-bit Android ABIs. Inspect actual downloaded
ELF PT_LOAD headers and packaged alignment; upstream build flags are not a
runtime result. Test actual 16 KiB hardware/emulator loading separately. No
32-bit 16 KiB compatibility claim is made.

Before release: run real recognition with synthetic English/Hindi/Marathi and
mixed-script fixtures, boxes/orientation/empty and damaged-page checks, bounded
cancellation and lock races, encrypted persistence/reopen, log/private-file
canaries, and physical-device latency/memory/16 KiB measurements. Keep those
results distinct from successful compilation and static artifact verification.
Finish the complete native notices and provenance audit for all app components.
No cloud fallback, network OCR, telemetry SDK or assistant model is enabled.
