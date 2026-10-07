package com.healthy.agent.knowledge;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pages.TextPolicy;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;

@Component
public class KnowledgeDocumentParser {
    private final int maxPreviewCharacters;
    private final int maxExtractedCharacters;

    @Autowired
    public KnowledgeDocumentParser(
            @Value("${agent.knowledge.preview.max-characters:12000}") int maxPreviewCharacters,
            @Value("${agent.knowledge.indexing.max-extracted-characters:2000000}") int maxExtractedCharacters
    ) {
        if (maxPreviewCharacters < 1 || maxExtractedCharacters < maxPreviewCharacters) {
            throw new IllegalArgumentException("Document character limits are invalid");
        }
        this.maxPreviewCharacters = maxPreviewCharacters;
        this.maxExtractedCharacters = maxExtractedCharacters;
    }

    KnowledgeDocumentParser(int maxPreviewCharacters) {
        this(maxPreviewCharacters, Math.max(maxPreviewCharacters, 2_000_000));
    }

    public KnowledgeDocumentPreview parse(KnowledgeDocumentMetadata document, InputStream input) {
        ExtractedText extracted = extract(document, input, maxPreviewCharacters);
        String preview = normalize(extracted.text());
        requireText(preview);

        return new KnowledgeDocumentPreview(
                document.id(),
                document.originalName(),
                document.contentType(),
                preview,
                extracted.totalCharacters(),
                preview.length(),
                extracted.truncated()
        );
    }

    public String extractText(KnowledgeDocumentMetadata document, InputStream input) {
        ExtractedText extracted = extract(document, input, maxExtractedCharacters);
        if (extracted.truncated()) {
            throw new AgentException(AgentErrorCode.DOCUMENT_TEXT_TOO_LARGE);
        }
        String text = normalize(extracted.text());
        requireText(text);
        return text;
    }

    private ExtractedText extract(KnowledgeDocumentMetadata document, InputStream input, int limit) {
        CountingPreviewWriter writer = new CountingPreviewWriter(limit);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, document.originalName());
        metadata.set(HttpHeaders.CONTENT_TYPE, document.contentType());

        ParseContext context = new ParseContext();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.pages().setText(TextPolicy.EXTRACT);
        context.set(PDFParserConfig.class, pdfConfig);

        try (TikaInputStream tikaInput = TikaInputStream.get(input)) {
            new AutoDetectParser().parse(tikaInput, new BodyContentHandler(writer), metadata, context);
        } catch (Exception exception) {
            throw new AgentException(AgentErrorCode.DOCUMENT_PARSE_FAILED);
        }
        return new ExtractedText(writer.preview(), writer.totalCharacters(), writer.truncated());
    }

    private String normalize(String value) {
        return value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private void requireText(String text) {
        if (text.isBlank()) {
            throw new AgentException(AgentErrorCode.DOCUMENT_TEXT_EMPTY);
        }
    }

    private record ExtractedText(String text, long totalCharacters, boolean truncated) {
    }

    private static final class CountingPreviewWriter extends Writer {
        private final int limit;
        private final StringBuilder preview;
        private long totalCharacters;

        private CountingPreviewWriter(int limit) {
            this.limit = limit;
            this.preview = new StringBuilder(limit);
        }

        @Override
        public void write(char[] characters, int offset, int length) {
            totalCharacters += length;
            int remaining = limit - preview.length();
            if (remaining > 0) {
                preview.append(characters, offset, Math.min(length, remaining));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws IOException {
        }

        private String preview() {
            return preview.toString();
        }

        private long totalCharacters() {
            return totalCharacters;
        }

        private boolean truncated() {
            return totalCharacters > limit;
        }
    }
}
