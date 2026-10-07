package com.healthy.agent.knowledge;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentFileValidatorTest {
    private final DocumentFileValidator validator = new DocumentFileValidator(1024 * 1024);

    @Test
    void acceptsPdfAndRemovesClientPath() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "C:\\fakepath\\医院制度.pdf",
                "application/pdf",
                "%PDF-1.7\ncontent".getBytes(StandardCharsets.UTF_8)
        );

        ValidatedDocument validated = validator.validate(file);

        assertThat(validated.fileName()).isEqualTo("医院制度.pdf");
        assertThat(validated.extension()).isEqualTo("pdf");
        assertThat(validated.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void acceptsValidDocxContainer() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "制度.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes()
        );

        assertThat(validator.validate(file).extension()).isEqualTo("docx");
    }

    @Test
    void acceptsUtf8Markdown() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "常见问题.md",
                "text/markdown",
                "# 就诊须知\n请携带身份证。".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(validator.validate(file).contentType()).startsWith("text/markdown");
    }

    @Test
    void rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "script.exe", "application/octet-stream", new byte[]{1, 2, 3}
        );

        assertError(file, AgentErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    void rejectsFileWhoseContentDoesNotMatchExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)
        );

        assertError(file, AgentErrorCode.FILE_CONTENT_MISMATCH);
    }

    @Test
    void rejectsMalformedUtf8Text() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "broken.txt", "text/plain", new byte[]{(byte) 0xC3, (byte) 0x28}
        );

        assertError(file, AgentErrorCode.FILE_CONTENT_MISMATCH);
    }

    @Test
    void rejectsTextContainingNullBytes() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "binary.txt", "text/plain", new byte[]{'a', 0, 'b'}
        );

        assertError(file, AgentErrorCode.FILE_CONTENT_MISMATCH);
    }

    @Test
    void rejectsOversizedFile() {
        DocumentFileValidator smallValidator = new DocumentFileValidator(4);
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "12345".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> smallValidator.validate(file))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(AgentErrorCode.FILE_TOO_LARGE));
    }

    private void assertError(MockMultipartFile file, AgentErrorCode expectedCode) {
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expectedCode));
    }

    private byte[] docxBytes() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
