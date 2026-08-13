# RFC 004: Report Diff View + Markdown / PDF Export

- **Status:** Draft
- **Date:** 2026-08-13
- **Author:** FinSight contributors
- **Tracker:** Issue TBD

## Context

`stock_analysis_reports` is already versioned
(`reportVersion` + `dataSnapshotHash`), the workflow orchestrator
persists a new row on every analysis, and `docs/api.md` documents
`GET /api/research/stock/{symbol}/reports` returning a list. The
missing features are:

1. **Diff view.** Given two `reportId`s, show what changed: rating
   flip, summary rewrite, evidence delta. This is the one feature
   that would justify saving every report.
2. **Export.** A user researching offline wants to share a single
   report with a colleague. The current API returns JSON only; the
   frontend has no "copy to clipboard" or "download" affordance.
3. **Stable export formats.** Markdown is the lingua franca; PDF is
   what people print. Both should round-trip deterministically.

`ROADMAP.md` lists both as Mid Term: "Add report diff view between
`reportVersion`s" and "Add report export to Markdown/PDF".

## Goals

1. `GET /api/research/stock/{symbol}/reports/{a}/diff/{b}` returns
   a structured diff that the static frontend can render side by
   side.
2. `GET /api/research/stock/{symbol}/reports/{id}.md` returns a
   CommonMark-formatted report with embedded citation links.
3. `GET /api/research/stock/{symbol}/reports/{id}.pdf` returns a
   paginated PDF with Chinese font embedding.
4. The static frontend gets a "diff" toggle in the AI analysis
   workspace and "export" buttons in the report history.
5. A regression IT verifies the diff output for known inputs and
   the export output for golden fixtures.

## Non-Goals

- Exporting charts (we'd need a headless chart rendering service).
- Real-time collaborative editing.
- Signed / encrypted PDFs.

## Design

### Diff service

`application/ReportDiffService.java`:

```java
public ReportDiff diff(String symbol, String fromId, String toId) {
    StockAnalysisReport from = reportRepository.findById(fromId).orElseThrow();
    StockAnalysisReport to   = reportRepository.findById(toId).orElseThrow();
    return new ReportDiff(
        ratingDiff(from.rating(), to.rating()),
        summaryDiff(from.summary(), to.summary()),
        positivesDiff(from.positivePoints(), to.positivePoints()),
        risksDiff(from.riskPoints(), to.riskPoints()),
        citationsDiff(from.citations(), to.citations()),
        contextHashChanged(!from.contextHash().equals(to.contextHash())),
        reportVersionDelta(to.reportVersion() - from.reportVersion())
    );
}
```

Each `*Diff` returns a list of `DiffSegment { String text; Op op }`
where `op ∈ {EQUAL, INSERT, DELETE}`. A standard Myers diff
algorithm (we'll add a small `LongestCommonSubsequence` helper or
pull `java-diff-utils`) is the engine.

### Markdown export

`application/ReportMarkdownExporter.java`:

```java
public String export(StockAnalysisReport report,
                     Company company,
                     List<FinancialMetric> metrics,
                     List<RiskSignal> risks) {
    var ctx = new Context(report, company, metrics, risks);
    return new TemplateEngine("export/report.md.tmpl").render(ctx);
}
```

Templates live in `backend/src/main/resources/templates/export/`:

- `report.md.tmpl` — main report
- `risk-table.md.tmpl` — markdown table of risk signals
- `metric-table.md.tmpl` — markdown table of metrics

We use `commonmark-java` for parsing user input if the report
contains markdown; otherwise we emit our own template.

### PDF export

`application/ReportPdfExporter.java` uses OpenPDF
(`com.github.librepdf:openpdf`) with a Chinese-capable TTF
embedded via `FontFactory.register()`. The page template is a
single CSS-Paged-Media stylesheet at
`backend/src/main/resources/templates/export/report.css`. The PDF
includes:

- Cover page: company, symbol, generated timestamp, model
- Section 1: rating + confidence gauge
- Section 2: summary
- Section 3: supporting evidence (citations)
- Section 4: risk signals
- Section 5: data snapshot hash + context hash

### API surface

```http
GET /api/research/stock/{symbol}/reports/{a}/diff/{b}
GET /api/research/stock/{symbol}/reports/{id}.md
GET /api/research/stock/{symbol}/reports/{id}.pdf
```

The diff endpoint is `application/json`; the others use
`Content-Type: text/markdown; charset=utf-8` and `application/pdf`
respectively. All three respect the existing
`X-CSRF-Token` requirement for personal access.

### Frontend integration

- `static/app.js`: in the AI analysis workspace, when the user
  opens the report history (`/api/research/stock/{symbol}/reports`),
  show a "compare to previous" toggle. Selecting a row issues
  `/diff/{latest.id}` and renders side-by-side.
- Export buttons (`/api/research/stock/{symbol}/reports/{id}.md`,
  `.pdf`) appear next to each report row.

### Regression IT

`ReportDiffServiceIT` and `ReportMarkdownExporterIT` are added to
the Testcontainers matrix (RFC 001). The diff IT loads a known
fixture (report A → report B), asserts 5 specific segments, and
verifies the JSON shape. The exporter IT golden-tests the
Markdown output against a checked-in fixture.

## Migration plan

1. Land `application/ReportDiffService.java` + `ReportDiff.java`
   DTO + `ReportDiffController.java`. Front-end stub call.
2. Add `application/ReportMarkdownExporter.java` + templates.
3. Add `application/ReportPdfExporter.java` + OpenPDF dep + CSS.
4. Wire the frontend affordances (diff toggle + export buttons).
5. Add ITs.
6. `docs/api.md` and `docs/user-guide.md` updates.

## Open questions

- PDF font licensing: OpenPDF requires the user to supply a TTF.
  We will vendor `Noto Sans CJK SC` (SIL Open Font License).
- Should the diff endpoint also return the underlying `dataSnapshotHash`
  delta, or is the `contextHashChanged` boolean enough? Decision:
  return both for transparency.
- Do we cache the rendered PDF? Yes, in `StockAnalysisCache` with
  a separate `CacheKey(symbol, reportId, "pdf")` namespace.

## Estimated LoC

- Diff service + DTO + controller: ~600 LoC
- Myers diff helper: ~120 LoC
- Markdown exporter + 3 templates: ~500 LoC
- PDF exporter + CSS + font vendor step: ~600 LoC
- Front-end wiring: ~250 LoC
- ITs: ~250 LoC
- **Total: ~2,300 LoC**
