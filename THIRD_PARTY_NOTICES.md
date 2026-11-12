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
