package com.healthy.agent.knowledge;

import com.healthy.agent.config.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;

@Component
public class KnowledgeDocumentStorage {
    private final MinioClient minioClient;
    private final MinioProperties properties;

    public KnowledgeDocumentStorage(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public void upload(
            String objectKey,
            MultipartFile file,
            ValidatedDocument validated,
            String documentId,
            String sha256,
            long uploaderId
    ) throws Exception {
        try (InputStream input = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .stream(input, file.getSize(), -1L)
                    .contentType(validated.contentType())
                    .userMetadata(Map.of(
                            "document-id", documentId,
                            "uploader-id", Long.toString(uploaderId),
                            "sha256", sha256
                    ))
                    .build());
        }
    }

    public void delete(String objectKey) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build());
    }

    public InputStream open(String objectKey) throws Exception {
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build());
    }
}
