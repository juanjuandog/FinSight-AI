# Issue 004: Report Diff View + Markdown / PDF Export

## Summary

Expose the existing `reportVersion` axis of `stock_analysis_reports`
through three new endpoints (diff, Markdown, PDF) and surface them
in the static frontend. Closes the Mid Term items in
`ROADMAP.md`: "Add report diff view between `reportVersion`s" and
"Add report export to Markdown/PDF".

## Motivation

- The data model is already versioned; the UI doesn't show it.
- Researchers need a way to share a single report with a
  colleague without sending the JSON blob.
- A diff is the strongest signal that the analysis has actually
  changed since the last snapshot — a precondition for the
  evidence-grounded story in the README.

## Tasks

- [ ] `application/ReportDiffService.java` + `domain/model/ReportDiff.java`
  + `api/ReportDiffController.java`.
- [ ] `application/diff/MyersDiff.java` (or `java-diff-utils`).
- [ ] `application/ReportMarkdownExporter.java` + 3 CommonMark
  templates under `backend/src/main/resources/templates/export/`.
- [ ] `application/ReportPdfExporter.java` using
  `com.github.librepdf:openpdf`; vendor
  `Noto Sans CJK SC` TTF.
- [ ] Cache PDFs in `StockAnalysisCache` with a
  `(symbol, reportId, "pdf")` key namespace.
- [ ] Frontend: add "compare to previous" toggle in AI analysis
  workspace; add `.md` and `.pdf` export buttons to the report
  history list.
- [ ] Regression ITs: `ReportDiffServiceIT` with 5-segment fixture
  assertion; `ReportMarkdownExporterIT` with golden test.
- [ ] `docs/api.md`: document the three new endpoints.
- [ ] `docs/user-guide.md`: short walkthrough of the diff
  workflow.

## Acceptance criteria

- `GET /api/research/stock/{symbol}/reports/{a}/diff/{b}` returns
  a JSON body that includes the rating, summary, positive points,
  risk points, and citations diff, plus a `contextHashChanged`
  boolean.
- The Markdown export of a real report contains the company name,
  rating, summary, ≥ 1 positive point, ≥ 1 risk point, and the
  `dataSnapshotHash` line.
- The PDF export of the same report opens in a stock reader, the
  Chinese characters render correctly, and the file size is
  ≤ 1.5 MB.
- The "compare to previous" toggle renders the JSON diff in a
  side-by-side view.
- The two ITs pass.

## Out of scope

- Exporting charts (would need a headless chart service).
- Signed PDFs.
- Real-time collaborative editing.

## References

- `docs/rfcs/RFC-004-report-diff-and-export.md`
- `ROADMAP.md` (Mid Term: diff + export)
- `backend/src/main/java/com/finsight/domain/model/StockAnalysisReport.java`

## Estimate

3 weeks. Split into 4 PRs:

1. Diff service + controller + IT (~700 LoC, 1 PR)
2. Markdown exporter + templates + IT (~600 LoC, 1 PR)
3. PDF exporter + font + IT (~700 LoC, 1 PR)
4. Frontend wiring + docs (~300 LoC, 1 PR)
