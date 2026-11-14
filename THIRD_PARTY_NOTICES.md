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


## Cafe Bazaar update IPC (5.5.6)

- Official [CafeBazaar/BazaarUpdater](https://github.com/cafebazaar/BazaarUpdater), **Apache-2.0**.
- Source reference pinned to `670bdfd0cf7bd57c03ec503a78e1bcddd8fe29b6`.
- The small `com.farsitel.bazaar.IUpdateCheckService` AIDL contract is adapted to retain
  only its first two methods, in their original Binder transaction order. Version threshold,
  explicit binding action/package and modal update URI follow the official source. No
  JitPack/Updater binary, installer, auto-download library or developer-account token is bundled.
- Complete Apache-2.0 terms are packaged in `res/raw/bazaar_updater_license.txt`, readable
  from **Settings → About → Bazaar update interface licence**.
- `android/bazaar-fixture` is a clearly labelled, no-network, CI-only simulated store.
  It is never a dependency of the application and its APK is never delivered to users.

## Retired offline OCR assets

Tesseract4Android/Tesseract/Leptonica and their bundled native dependencies, all tessdata
models and the Parastoo print-reference fonts are **removed from 5.5.6**. No native model
or corresponding print-reference font is shipped or downloaded. Historical version notices
remain available in Git history; the active application retains the Vazirmatn licence above.

## Isfahan route coordinates (5.5.7)

The three new fixed-route endpoint coordinate pairs are derived from **© OpenStreetMap
contributors**, under [ODbL 1.0](https://www.openstreetmap.org/copyright). Attribution
is visible in the traffic screen. Public source ways/nodes, endpoints, directions
and limitations are documented in [docs/TRAFFIC-ROUTES.md](docs/TRAFFIC-ROUTES.md).
No bulk map database or map tiles are bundled; Neshan traffic responses are fetched
only using the owner's configured Neshan key.
