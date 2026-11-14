# 5.4.3 · ruled-form preprocessing, blank-hours review and full-period sparse comparison

Upgrade of `com.khodroyar.app`, versionCode **65**, original signing identity and Room **6**.
No schema migration beyond the review-draft version 3 reader (which still reads 1 and 2),
no service writer, income/export rewrite, new permission or cloud OCR. The already-published
5.4.1 and 5.4.2 APKs/Releases must not be overwritten.

## Deterministic offline preprocessing (same bundled engines, nothing sent anywhere)

- Coarse ±8° deskew in .4° steps with .05° refinement, applied through bounds-checked filtered
  rotation only when the ruled-form evidence supports it; page geometry is otherwise untouched.
- Bounded per-tile paper illumination (interpolated, mild gain ≤1.45) so a dark digit/slash can
  never be brightened away, lightened away, or fogged; uniform pages are returned unchanged.
- Conservative ruled-table white-padding with .015 coordinate margin that keeps the title/month
  strip and every closed row; with faint or missing rules, substantive ink below the grid keeps
  the **entire page** instead of cropping unreadable rows. Geometry comes only from actual closed
  multi-column rules of this page.
- Adaptive local-mean fourth view for the classic reader, erosions, denoise and digit repair stay
  **out**: no thin stroke (small digits, `/`) is ever removed or invented. Raster work is bounded
  to the existing 6 MP / 3200-edge plan, reuses one pixel buffer and is cancellation-checked.

## Fixed-template cell policy (ordinal · date · km · available hours)

- A **lone canonically valid** reading is enough: Tesseract ≥ **75** (this reviewed form only),
  qualified distance-gated glyph ≥ 70, classic native ≥ 85. Agreement never boosts a score.
- **Two strong disagreeing readings leave the cell blank for manual review**; a weak candidate can
  never override a genuine strong conflict (including conflicts inside the classic multi-view read).
  Non-template thresholds and grammar gates are unchanged.
- Numeric company cells read as single tokens; date cells keep line segmentation with «جمع»/footer.
- Generic (non-template) pages keep the previous ≥85/grammar behaviour — nothing is relaxed there.

## Empty hours are valid empty, never zero

- Empty, whitespace, `.` or `-` hours (or a genuinely blank-ink cell) are **valid empty hours**:
  the row survives when date and/or km are recovered, no failure drops it, and **no zero is stored,
  compared or displayed**. A manually typed `0` remains a real recorded zero.
- Reviews are nullable end-to-end: rows with absent hours show specific editable field messages
  («صفر فرض نشده و اختلاف ساعت نامشخص می‌ماند») instead of failing the whole row.
- Date cleanup is formatting only — Persian/Arabic→Latin digits, separator-spacing removal,
  repeated `/` `-` `.` collapse, supported separated/compact Jalali formats; invalid days
  (e.g. 30 Esfand in a common year) and uncertain digits are rejected, never repaired.

## Full-period comparison with unknown hours

- Sparse comparison aggregates **reviewed company rows by date** and **actual app records over the
  complete reporting period** (`servicesInRangeOnce`, real IDs and stored manual hours — never
  elapsed start/end time). It shows full-period company/app service counts, km totals, missing and
  extra days, and **nullable** hours/differences; nothing unknown is rendered as zero.
- Strict `ServiceWorkComparison` is untouched and runs only on days whose hours are fully known
  (shown honestly as «روزهای دارای اطلاعات کامل», never as whole-period totals).
- Days with unknown hours hold **all** candidates for explicit review; grouped statements suspend
  service counts for their whole scope; manual review links that touch a held day are deferred
  **transitively** so no partial leak into automatic matching occurs. No ambiguous pairing is guessed.
- Draft persistence v3 records blank-hours evidence (backward-compatible reads of v1/v2), and the
  release instrumentation keeps its exact generic and fixed-company signed-APK proofs.
