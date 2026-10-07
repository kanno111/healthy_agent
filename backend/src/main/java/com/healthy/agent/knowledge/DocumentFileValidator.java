package com.healthy.agent.knowledge;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class DocumentFileValidator {
    private static final int MAX_FILE_NAME_LENGTH = 180;
    private static final int MAX_DOCX_ENTRIES_TO_SCAN = 500;
    private static final int TEXT_BUFFER_SIZE = 4096;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md", "markdown");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "txt", "text/plain; charset=utf-8",
            "md", "text/markdown; charset=utf-8",
            "markdown", "text/markdown; charset=utf-8"
    );

    private final long maxFileSizeBytes;

    public DocumentFileValidator(
            @Value("${agent.knowledge.upload.max-file-size-bytes:20971520}") long maxFileSizeBytes
    ) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    ValidatedDocument validate(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null) {
            throw new AgentException(AgentErrorCode.INVALID_FILE);
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new AgentException(AgentErrorCode.FILE_TOO_LARGE);
        }

        String fileName = sanitizeFileName(file.getOriginalFilename());
        String extension = extensionOf(fileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new AgentException(AgentErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        try {
            boolean validContent = switch (extension) {
                case "pdf" -> isPdf(file);
                case "docx" -> isDocx(file);
                case "txt", "md", "markdown" -> isUtf8Text(file);
                default -> false;
            };
            if (!validContent) {
                throw new AgentException(AgentErrorCode.FILE_CONTENT_MISMATCH);
            }
        } catch (IOException exception) {
            throw new AgentException(AgentErrorCode.FILE_CONTENT_MISMATCH);
        }

        return new ValidatedDocument(fileName, extension, CONTENT_TYPES.get(extension));
    }

    private String sanitizeFileName(String originalFileName) {
        String normalized = originalFileName.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
            throw new AgentException(AgentErrorCode.INVALID_FILE);
        }
        if (fileName.length() <= MAX_FILE_NAME_LENGTH) {
            return fileName;
        }

        String extension = extensionOf(fileName);
        int extensionLength = extension.isBlank() ? 0 : extension.length() + 1;
        int baseLength = MAX_FILE_NAME_LENGTH - extensionLength;
        return fileName.substring(0, baseLength) + (extension.isBlank() ? "" : "." + extension);
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 1 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isPdf(MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            byte[] signature = input.readNBytes(5);
            return signature.length == 5
                    && signature[0] == '%'
                    && signature[1] == 'P'
                    && signature[2] == 'D'
                    && signature[3] == 'F'
                    && signature[4] == '-';
        }
    }

    private boolean isDocx(MultipartFile file) throws IOException {
        boolean contentTypesFound = false;
        boolean documentXmlFound = false;
        int scannedEntries = 0;
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && scannedEntries++ < MAX_DOCX_ENTRIES_TO_SCAN) {
                if ("[Content_Types].xml".equals(entry.getName())) {
                    contentTypesFound = true;
                } else if ("word/document.xml".equals(entry.getName())) {
                    documentXmlFound = true;
                }
                if (contentTypesFound && documentXmlFound) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isUtf8Text(MultipartFile file) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(file.getInputStream(), decoder)) {
            char[] buffer = new char[TEXT_BUFFER_SIZE];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                for (int index = 0; index < count; index++) {
                    if (buffer[index] == '\0') {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
