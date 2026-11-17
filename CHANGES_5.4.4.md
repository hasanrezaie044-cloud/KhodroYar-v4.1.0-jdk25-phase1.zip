# 5.4.4 · optional explicit-consent online prefill + offline fixes

Upgrade of `com.khodroyar.app`, versionCode **66**, original signing identity and Room **6**.
Everything from 5.4.3 remains: deterministic offline preprocessing is still the default and the
signed-runtime verification still runs fully offline with radios disabled.

## Optional online assist (default OFF, never silent)

- New «پیش‌پر اختیاری آنلاین» button in the work-report screen. Only after the user types their
  own HTTPS endpoint and confirms a dialog that names the receiving host does the app post at most
  10 page images (≤1.2 MB JPEG each, bounded re-encode) — once, for that period only.
- Pixel limits, no redirects/cookies, ≤2 MB responses, HTTPS without user info; on any transport or
  contract error nothing changes and the offline result remains.
- The response only **prefills the same editable review table** through the unchanged evidence
  policy: blank/dot/dash hours stay unknown, strong conflicts stay blank, no zeros are invented,
  strict `ServiceWorkComparison` is untouched, and the user still reviews/compares. Endpoint
  contract: docs/ONLINE-OCR-ASSIST.md. Provenance is marked in the period note.

## Offline-path fixes seen on a real photo

- `isGrouped` no longer substring-matches: route names like «فولاد و پالبکس» can no longer be
  mistaken for «نیمه»/«کارکرد» fragments — only whole tokens mark a row grouped.
- Glyph row reading de-duplicates identical regions so a region appearing in two grouped lines is
  never computed twice (the crash vector behind failed row reads on overlapping line groups).

No new permission (INTERNET already existed for the optional assistant), no schema change beyond
draft-version-3 continuation, no change to publication gates.
