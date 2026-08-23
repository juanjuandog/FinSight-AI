package com.finsight.application;

import com.finsight.domain.model.Company;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.domain.model.StockAnalysisReport;
import com.lowagie.text.Anchor;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.awt.Color;
import java.util.Comparator;
import java.util.List;

@Component
public class ReportPdfExporter {
    private static final String FONT_RESOURCE = "fonts/NotoSansSC-VF.ttf";
    private static final Color INK = new Color(46, 48, 47);
    private static final Color MUTED = new Color(98, 103, 98);
    private static final Color GOLD = new Color(128, 106, 67);
    private static final Color PEARL = new Color(247, 245, 239);
    private static final Color LINE = new Color(222, 217, 205);

    public byte[] export(
            StockAnalysisReport report,
            Company company,
            List<FinancialMetric> metrics,
            List<RiskSignal> risks
    ) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BaseFont baseFont = loadFont();
            Document document = new Document(PageSize.A4, 52, 52, 54, 50);
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.setPdfVersion(PdfWriter.VERSION_1_7);
            writer.setViewerPreferences(PdfWriter.PageModeUseOutlines);
            document.addTitle(company.name() + "（" + report.companySymbol() + "）研究报告");
            document.addAuthor("FinSight AI");
            document.addSubject("Evidence-grounded equity research report");
            document.open();

            addCover(document, report, company, baseFont);
            document.newPage();
            addReportBody(document, report, metrics, risks, baseFont);
            document.close();
            return output.toByteArray();
        } catch (DocumentException ex) {
            throw new IllegalStateException("Unable to render research report PDF", ex);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to render research report PDF", ex);
        }
    }

    private void addCover(Document document, StockAnalysisReport report, Company company, BaseFont baseFont)
            throws DocumentException {
        Paragraph eyebrow = paragraph("FINSIGHT AI · 研究报告", font(baseFont, 9, Font.BOLD, GOLD));
        eyebrow.setSpacingBefore(72);
        eyebrow.setSpacingAfter(24);
        document.add(eyebrow);

        Paragraph title = paragraph(company.name(), font(baseFont, 28, Font.BOLD, INK));
        title.setLeading(36);
        title.setSpacingAfter(8);
        document.add(title);

        Paragraph symbol = paragraph(report.companySymbol() + " · " + company.exchange() + " · " + company.industry(),
                font(baseFont, 11, Font.NORMAL, MUTED));
        symbol.setSpacingAfter(40);
        document.add(symbol);

        PdfPTable summary = new PdfPTable(new float[]{1, 1, 1});
        summary.setWidthPercentage(100);
        summary.setSpacingAfter(34);
        summary.addCell(summaryCell("研究评级", report.rating(), baseFont));
        summary.addCell(summaryCell("置信度", report.confidence() + "%", baseFont));
        summary.addCell(summaryCell("报告版本", "v" + report.reportVersion(), baseFont));
        document.add(summary);

        Paragraph abstractTitle = paragraph("研究摘要", font(baseFont, 11, Font.BOLD, GOLD));
        abstractTitle.setSpacingAfter(10);
        document.add(abstractTitle);
        Paragraph abstractText = paragraph(value(report.summary(), "暂无分析摘要。"), font(baseFont, 15, Font.NORMAL, INK));
        abstractText.setLeading(25);
        abstractText.setSpacingAfter(42);
        document.add(abstractText);

        Paragraph meta = paragraph(
                "生成时间  " + report.generatedAt() + "\n模型  " + value(report.model(), "unknown")
                        + "\n数据快照  " + value(report.dataSnapshotHash(), "—"),
                font(baseFont, 9, Font.NORMAL, MUTED)
        );
        meta.setLeading(16);
        document.add(meta);
    }

    private void addReportBody(
            Document document,
            StockAnalysisReport report,
            List<FinancialMetric> metrics,
            List<RiskSignal> risks,
            BaseFont baseFont
    ) throws DocumentException {
        addSectionTitle(document, "支持依据", baseFont);
        addBullets(document, report.positivePoints(), "暂无支持依据。", baseFont);

        addSectionTitle(document, "风险与待确认项", baseFont);
        addBullets(document, report.riskPoints(), "暂无风险信号。", baseFont);
        addRiskTable(document, risks, baseFont);

        addSectionTitle(document, "核心财务指标", baseFont);
        addMetricTable(document, metrics, baseFont);

        addSectionTitle(document, "证据引用", baseFont);
        addCitations(document, report.citations(), baseFont);

        addSectionTitle(document, "数据快照", baseFont);
        Paragraph hashes = paragraph(
                "数据快照哈希  " + value(report.dataSnapshotHash(), "—")
                        + "\n上下文哈希  " + value(report.contextHash(), "—")
                        + "\n分析来源  " + value(report.source(), "—"),
                font(baseFont, 8, Font.NORMAL, MUTED)
        );
        hashes.setLeading(15);
        document.add(hashes);

        Paragraph disclaimer = paragraph(
                "本报告基于生成时可获得的数据与证据，仅用于研究记录，不构成投资建议。",
                font(baseFont, 8, Font.NORMAL, MUTED)
        );
        disclaimer.setSpacingBefore(28);
        document.add(disclaimer);
    }

    private void addSectionTitle(Document document, String title, BaseFont baseFont) throws DocumentException {
        Paragraph heading = paragraph(title, font(baseFont, 14, Font.BOLD, INK));
        heading.setSpacingBefore(18);
        heading.setSpacingAfter(10);
        document.add(heading);
    }

    private void addBullets(Document document, List<String> values, String empty, BaseFont baseFont)
            throws DocumentException {
        List<String> items = values == null || values.isEmpty() ? List.of(empty) : values;
        for (String item : items) {
            Paragraph paragraph = paragraph("•  " + value(item, empty), font(baseFont, 10, Font.NORMAL, INK));
            paragraph.setLeading(17);
            paragraph.setIndentationLeft(8);
            paragraph.setSpacingAfter(5);
            document.add(paragraph);
        }
    }

    private void addMetricTable(Document document, List<FinancialMetric> metrics, BaseFont baseFont)
            throws DocumentException {
        PdfPTable table = table(new float[]{1.1f, 2.4f, 1.3f, 1.5f});
        addHeader(table, baseFont, "财年", "指标", "数值", "公式版本");
        List<FinancialMetric> values = metrics == null ? List.of() : metrics.stream()
                .sorted(Comparator.comparing(FinancialMetric::fiscalYear).reversed()
                        .thenComparing(FinancialMetric::code))
                .limit(12)
                .toList();
        if (values.isEmpty()) {
            addRow(table, baseFont, "—", "暂无财务指标", "—", "—");
        } else {
            for (FinancialMetric metric : values) {
                addRow(table, baseFont, metric.fiscalYear().toString(), metric.name(), format(metric.value()),
                        metric.formulaVersion());
            }
        }
        document.add(table);
    }

    private void addRiskTable(Document document, List<RiskSignal> risks, BaseFont baseFont) throws DocumentException {
        PdfPTable table = table(new float[]{1.2f, 2.1f, 0.8f, 3.2f});
        table.setSpacingBefore(10);
        addHeader(table, baseFont, "日期", "风险信号", "等级", "说明");
        List<RiskSignal> values = risks == null ? List.of() : risks.stream()
                .sorted(Comparator.comparing(RiskSignal::detectedAt).reversed())
                .limit(12)
                .toList();
        if (values.isEmpty()) {
            addRow(table, baseFont, "—", "暂无结构化风险信号", "—", "—");
        } else {
            for (RiskSignal risk : values) {
                addRow(table, baseFont, risk.detectedAt().toString(), risk.title(), Integer.toString(risk.severity()),
                        risk.explanation());
            }
        }
        document.add(table);
    }

    private void addCitations(Document document, List<String> citations, BaseFont baseFont) throws DocumentException {
        List<String> values = citations == null || citations.isEmpty() ? List.of("暂无引用。") : citations;
        for (String citation : values) {
            String text = value(citation, "暂无引用。");
            Paragraph line = new Paragraph();
            line.setLeading(16);
            line.setSpacingAfter(5);
            line.add(new Phrase("•  ", font(baseFont, 9, Font.NORMAL, GOLD)));
            Anchor anchor = new Anchor(text, font(baseFont, 9, Font.NORMAL, INK));
            if (text.startsWith("https://") || text.startsWith("http://")) {
                anchor.setReference(text);
            }
            line.add(anchor);
            document.add(line);
        }
    }

    private PdfPCell summaryCell(String label, String value, BaseFont baseFont) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(PEARL);
        cell.setBorderColor(LINE);
        cell.setPadding(13);
        Paragraph content = new Paragraph();
        content.add(new Phrase(label + "\n", font(baseFont, 8, Font.NORMAL, MUTED)));
        content.add(new Phrase(value, font(baseFont, 15, Font.BOLD, INK)));
        cell.addElement(content);
        return cell;
    }

    private PdfPTable table(float[] widths) throws DocumentException {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSplitLate(false);
        table.setSpacingAfter(8);
        return table;
    }

    private void addHeader(PdfPTable table, BaseFont baseFont, String... values) {
        for (String value : values) {
            PdfPCell cell = cell(value, baseFont, Font.BOLD, MUTED);
            cell.setBackgroundColor(PEARL);
            table.addCell(cell);
        }
    }

    private void addRow(PdfPTable table, BaseFont baseFont, String... values) {
        for (String value : values) {
            table.addCell(cell(value, baseFont, Font.NORMAL, INK));
        }
    }

    private PdfPCell cell(String value, BaseFont baseFont, int style, Color color) {
        PdfPCell cell = new PdfPCell(new Phrase(value(value, "—"), font(baseFont, 8, style, color)));
        cell.setBorderColor(LINE);
        cell.setPadding(7);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private BaseFont loadFont() throws IOException, DocumentException {
        ClassPathResource resource = new ClassPathResource(FONT_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readAllBytes();
            return BaseFont.createFont("NotoSansSC-VF.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
        }
    }

    private Paragraph paragraph(String value, Font font) {
        return new Paragraph(value, font);
    }

    private Font font(BaseFont baseFont, float size, int style, Color color) {
        return new Font(baseFont, size, style, color);
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String format(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
