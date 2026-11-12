# Third-party asset notices

## Vazirmatn font

- Author/project: [Saber Rastikerdar / Vazirmatn](https://github.com/rastikerdar/vazirmatn)
- Licence: **SIL Open Font License 1.1** (copyright and complete terms are packaged in
  `android/app/src/main/res/raw/vazirmatn_license.txt`).
- Upstream revision: `6e553e33489a8f9dfaccc76860a2e3f3c1e66de7`.
- Unmodified upstream TTF files, from `fonts/ttf/`: Regular, Medium, SemiBold, Bold.
- Bundled under `android/app/src/main/res/font/`; combined TTF size **491,364 bytes**.
- These fonts load locally. No font service, API key or Internet request is used.

The font is distributed as part of the application, not sold on its own. Original font
names and copyright notices are preserved. The OFL text is retained as an Android raw
resource (`res/raw/vazirmatn_license.txt`) as well as in source.


## Bundled offline OCR (5.4.1, from the 5.4.0 baseline)

- Tesseract4Android **4.9.0**, Apache-2.0, Copyright 2019 Adaptech s.r.o. / Robert Pösel,
  pinned source `15c534717b1cb58261b58d4e4c1200c7f81f668c` in
  https://github.com/adaptech-cz/Tesseract4Android . Standard, single-threaded JNI engine.
- Included upstream native dependencies: Tesseract **5.5.1** (Apache-2.0), Leptonica **1.85.0**
  (BSD-style), libpng **1.6.48** (PNG Reference Library licence), libjpeg **9f** (IJG licence).
  This software is based in part on the work of the Independent JPEG Group.
- `fas` **tessdata_best** (Apache-2.0), pinned commit `e12c65a915945e4c28e237a9b52bc4a8f39a0cec`
  from https://github.com/tesseract-ocr/tessdata_best ; `ara` and `eng` **tessdata_fast** (Apache-2.0),
  pinned commit `87416418657359cb625c412a48b6e1d6d41c29bd` from https://github.com/tesseract-ocr/tessdata_fast .
  Full model licence and byte/hash provenance are bundled at `assets/ocr/LICENSE` and
  `assets/ocr/manifest.json`. `tools/verify-ocr-assets.py` checks every source asset;
  `tools/audit-ocr-apk.py` checks the actual packaged bytes.

Complete native upstream notices (including the IJG README/terms) are bundled in
`res/raw/ocr_licenses.txt` and accessible from **Settings → About → Offline OCR licences**.
No runtime model/network download is performed. These are OCR language models, not a generative LLM.

## Parastoo printed-number references (5.4.2)

- Saber Rastikerdar / [Parastoo](https://github.com/rastikerdar/parastoo-font), **SIL OFL 1.1**.
- Unmodified `dist/Parastoo.ttf` and `dist/Parastoo-Bold.ttf`, pinned revision
  `ab17e3b12fe84456c93e2d66e3e28231ae40c2de`, **192,120 bytes** combined.
- Bundled as `res/font/parastoo_regular.ttf` and `res/font/parastoo_bold.ttf`; original internal
  font names/copyrights are unchanged. Used only as local visual print references, not as a
  replacement for the app's typography. No document-derived glyphs or private reports are bundled.
- Complete upstream licence: `res/raw/parastoo_license.txt`, accessible in Settings → About →
  Offline OCR licences. Exact byte/hash/blob provenance: `assets/ocr/print-references.json`.
  Source and actual-APK audit tools verify both fonts and the licence. No runtime download.

## Supplemental classic Persian print model (5.4.2)

- Official [Tesseract tessdata](https://github.com/tesseract-ocr/tessdata), **Apache-2.0**;
  upstream states that all repository data, including the 3.04 tree, use this licence.
- Unmodified `fas.traineddata` from `3.04.00`, revision
  `074c37215b01ab8cc47a0e06ff7356383883d775`, renamed on disk to `fas_print.traineddata`
  to keep it separate from the LSTM model. **4,803,733 bytes**; SHA-256
  `5d4642e32c66e9450bca9c0ced22cae93188340acfd8c6ca717f02e617b9c154`.
- Uses the existing native classic classifier only for otherwise unreadable fixed-form
  cells/printed footer labels. Three bounded grayscale/contrast/threshold views; original
  native numeric confidence must be at least 85 and accepted views must agree. No online
  recognition, personal training samples, guessed digits, amount/month-specific correction,
  new JNI library or automatic-learning cache. The generic LSTM path is preserved.
- Complete Apache-2.0 terms remain in `assets/ocr/LICENSE` and the offline Settings notice.
  `assets/ocr/manifest.json` records alias, original filename, source commit/blob and exact
  bytes; source/APK audits check the asset and its legacy classifier component.
