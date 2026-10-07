package com.healthy.agent.knowledge;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentParserTest {

    @Test
    void parsesMarkdownAndLimitsPreviewWithoutHoldingAllText() {
        KnowledgeDocumentParser parser = new KnowledgeDocumentParser(12);
        byte[] content = "# 就诊制度\n患者应当提前到院办理手续。".getBytes(StandardCharsets.UTF_8);

        KnowledgeDocumentPreview result = parser.parse(
                metadata("制度.md", "text/markdown; charset=utf-8"),
                new ByteArrayInputStream(content)
        );

        assertThat(result.preview()).contains("就诊制度");
        assertThat(result.previewCharacters()).isLessThanOrEqualTo(12);
        assertThat(result.extractedCharacters()).isGreaterThan(result.previewCharacters());
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void parsesDocxText() throws Exception {
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("门诊预约须知");
            document.write(output);
            documentBytes = output.toByteArray();
        }

        KnowledgeDocumentPreview result = new KnowledgeDocumentParser(1000).parse(
                metadata("须知.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                new ByteArrayInputStream(documentBytes)
        );

        assertThat(result.preview()).contains("门诊预约须知");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void parsesPdfTextWithoutOcr() throws Exception {
        byte[] documentBytes;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Healthy appointment policy");
                content.endText();
            }
            document.save(output);
            documentBytes = output.toByteArray();
        }

        KnowledgeDocumentPreview result = new KnowledgeDocumentParser(1000).parse(
                metadata("policy.pdf", "application/pdf"),
                new ByteArrayInputStream(documentBytes)
        );

        assertThat(result.preview()).contains("Healthy appointment policy");
    }

    @Test
    void reportsEmptyTextForScannedOrBlankDocument() throws Exception {
        KnowledgeDocumentParser parser = new KnowledgeDocumentParser(1000);
        byte[] documentBytes;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            documentBytes = output.toByteArray();
        }

        assertThatThrownBy(() -> parser.parse(
                metadata("scanned.pdf", "application/pdf"),
                new ByteArrayInputStream(documentBytes)
        )).isInstanceOfSatisfying(AgentException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(AgentErrorCode.DOCUMENT_TEXT_EMPTY));
    }

    private KnowledgeDocumentMetadata metadata(String fileName, String contentType) {
        return new KnowledgeDocumentMetadata(
                "document-id", fileName, "documents/document-id/" + fileName,
                contentType, 10, "a".repeat(64), 12,
                false, 0, null,
                Instant.parse("2026-10-06T12:00:00Z"),
                Instant.parse("2026-10-06T12:00:00Z")
        );
    }
}
