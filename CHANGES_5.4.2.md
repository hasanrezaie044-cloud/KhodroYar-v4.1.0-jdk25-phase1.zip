# 5.4.2 · fixed company-form and individual-service comparison

Upgrade of `com.khodroyar.app`, versionCode **64**, original signing identity and Room **6**.
No schema migration, service writer, income/export rewrite, new permission or cloud OCR.
The already-published 5.4.1 APK/Release must not be overwritten.

- Recognise the recurring ordinal/date/origin/destination/km/available-hours form without a
  count header. Keep several same-day services distinct; ordinal is never a service count.
- Bounded local-contrast grid/skew detection and per-cell OCR preserve even completely
  unreadable rows. Exact raster scaling avoids unnecessarily halving a slightly oversized
  photo. Unknown cells and conflicting strong readings stay unknown; thresholds are not relaxed.
- Re-read a header strip when sparse OCR omits a short title, using recognised neighbouring
  labels and both native engines, retaining both ruled and rule-removed views for segmentation.
  Missing anchors come only from newly read label pixels; no column coordinate, prior page or
  numeric value substitutes for an unreadable title.
- Keep numeric glyphs on the actual row baseline; normalising a short Arabic five to its own
  cell height must not turn it into a Western zero. Fixed-form numeric cells use native
  single-token segmentation; date/description cells keep line segmentation. Actual fixed-form
  headings select the Persian/English LSTM pair before reading any cell, avoiding competing
  Arabic-fast numeric priors. Generic pages/headers keep all three languages. The same engine
  is reconfigured (no third concurrent engine); options/page images are restored after init.
  No result-driven model switching: strong conflicts remain unknown with the same gates.
- Read actual service records with their IDs and stored manual hours. Compare individual
  date/km/hour multiplicities before offering differences or missing records; show route,
  type and start/end as review context, never as invented work hours.
- Propose the whole Jalali reporting month, with leap-aware end dates and explicit review.
  Conflicting months require an explicit range. App-only beginning/end-of-month days remain in scope.
- Repeated scan evidence includes printed ordinal/date/km/hours; different ordinals preserve
  legitimate equal trips. Footer totals and salary/payment lines are not services.
- Identical-metric identities, split/merged trips and company summaries are honestly ambiguous.
  Read-only many-to-many manual links can determine coverage; stale/overlapping links fail closed.
  Grouped totals are not advertised as proof of every underlying service. No nearest-value guesses.
- Recoverable private draft v2 reads v1 without inventing service identity. Any edit/link/range
  change invalidates review; changing database records invalidates the old result.
- Add a source-pinned official Apache-2.0 classic Persian print model, only as a fallback
  for unknown fixed-form cells/footers. Three bounded photometric views must have an original
  native score >=85 and consistent accepted values; tiny ink and conflicts stay unknown.
  No local learning/private glyph dataset. All four models total 13,674,832 bytes.
- Add source-pinned OFL Parastoo print references with complete offline licence/provenance.
  No document-derived glyphs, employee data, salary amounts or private images are committed.

Tests: synthetic parser/month/raster/grid/matching/link fixtures, actual offline native three-page
PDF + repeated PNG and Room/UI comparison, draft recovery and signed R8/SAF fixed-form assertions.
Only the commit-specific CI results establish that these tests and the installable APK passed;
no physical Samsung or perfect recognition of every printed/photo glyph is claimed.
