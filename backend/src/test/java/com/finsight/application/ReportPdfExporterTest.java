package com.finsight.application;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPdfExporterTest {

    @Test
    void rendersCompactMultiPagePdfWithSearchableChineseText() throws Exception {
        byte[] pdf = new ReportPdfExporter().export(
                ReportMarkdownExporterTest.report(),
                ReportMarkdownExporterTest.company(),
                ReportMarkdownExporterTest.metrics(),
                ReportMarkdownExporterTest.risks()
        );

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.length).isLessThanOrEqualTo(1_500_000);

        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            assertThat(extractor.getTextFromPage(1)).contains("贵州茅台", "研究评级", "积极");
            assertThat(extractor.getTextFromPage(2)).contains("支持依据", "风险与待确认项", "不构成投资建议");
        } finally {
            reader.close();
        }
    }
}
