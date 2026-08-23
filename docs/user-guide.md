# FinSight AI User Guide

## Compare report versions

1. Open **AI 分析** and choose a company.
2. Generate an analysis. Every completed run creates an immutable report version.
3. In **研究档案 / 报告版本记录**, review the version number, rating, generated
   time, model, and data snapshot shown for each report.
4. After at least two versions exist, enable **与上一版对比**. FinSight compares
   the newest report with the immediately preceding version.
5. Read the two columns as **之前** and **现在**. Every section is also marked
   **已变化** or **未变化**, so color is not the only change signal. On a narrow
   screen, the same comparison stacks vertically.
6. Use **收起对比** or disable the toggle to return to the history list.

The comparison covers the research rating, conclusion summary, supporting points,
risk points, and citations. The header also states whether the research context or
underlying data snapshot changed. This helps distinguish a rewritten conclusion
from a conclusion generated against different evidence.

## Export a saved report

Each history row has two download actions:

- **Markdown** downloads a UTF-8 CommonMark document suitable for a repository,
  notes app, or further editing.
- **PDF** downloads a paginated, Chinese-capable report suitable for sharing or
  printing.

Exports are tied to the selected immutable report id. Generating a newer analysis
does not alter an older download. The files include the company, rating, confidence,
summary, supporting and risk points, citations, metrics, and snapshot hashes.

Charts, signed PDFs, and collaborative editing are intentionally outside this
workflow. Exports are research records and do not constitute investment advice.
