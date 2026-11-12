# Optional OCR component — provenance and licences

The primary application does not bundle an OCR runtime, trained data, ALBERT, ONNX Runtime, SentencePiece or any large language model.

The separately installable `com.khodroyar.ocr` component uses:

| Component | Pinned release | Upstream/licence |
|---|---|---|
| Tesseract4Android standard | 4.9.0 | [Adaptech wrapper](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0), Apache-2.0 |
| Tesseract | 5.5.1, supplied by wrapper | [Tesseract](https://github.com/tesseract-ocr/tesseract), Apache-2.0 |
| Leptonica | 1.85.0, supplied by wrapper | [Leptonica](https://github.com/DanBloomberg/leptonica), BSD-style |
| libpng | 1.6.48, supplied by wrapper | [libpng](https://github.com/pnggroup/libpng), libpng licence |
| libjpeg | v9f, supplied by wrapper | [Wrapper dependency source](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0), Independent JPEG Group licence |
| Persian `fas.traineddata` | immutable revision in `persian-ocr-data-lock.json` | [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast), Apache-2.0 |

The upstream wrapper README identifies the native dependency versions. The upstream source and complete licence texts are available at the pinned source links. Language data is optional and distributed separately, not embedded in either release APK. Its exact SHA-256 and byte length are verified by the provisioning tool and by the companion before use.

The selected Persian model supports printed Persian script and numerals. This is not an assertion of perfect table extraction, handwriting recognition, or measured payroll-statement accuracy. The complete human-review requirements are in `OFFLINE-RECONCILIATION.md`.
