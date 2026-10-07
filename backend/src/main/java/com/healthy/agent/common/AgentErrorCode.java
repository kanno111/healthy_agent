package com.healthy.agent.common;

import org.springframework.http.HttpStatus;

public enum AgentErrorCode {
    INVALID_FILE(40010, "请选择有效的知识库文档", HttpStatus.BAD_REQUEST),
    UNSUPPORTED_FILE_TYPE(40011, "仅支持 PDF、DOCX、TXT、MD 和 Markdown 文件", HttpStatus.BAD_REQUEST),
    FILE_CONTENT_MISMATCH(40012, "文件内容与扩展名不匹配或文件已损坏", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40100, "未登录或登录已失效", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, "无权访问当前资源", HttpStatus.FORBIDDEN),
    DOCUMENT_NOT_FOUND(40410, "文档不存在或已被删除", HttpStatus.NOT_FOUND),
    FILE_TOO_LARGE(41310, "单个文档不能超过 20 MiB", HttpStatus.CONTENT_TOO_LARGE),
    DOCUMENT_PARSE_FAILED(42210, "文档内容解析失败", HttpStatus.UNPROCESSABLE_CONTENT),
    DOCUMENT_TEXT_EMPTY(42211, "未提取到文本，扫描版 PDF 需要后续接入 OCR", HttpStatus.UNPROCESSABLE_CONTENT),
    DOCUMENT_TEXT_TOO_LARGE(42212, "文档提取文本过大，暂不支持构建索引", HttpStatus.UNPROCESSABLE_CONTENT),
    STORAGE_UNAVAILABLE(50310, "文档存储服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE),
    METADATA_UNAVAILABLE(50311, "文档元数据服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE),
    INDEX_UNAVAILABLE(50312, "文档索引服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE),
    EMBEDDING_UNAVAILABLE(50313, "文档向量化服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(50000, "服务暂时不可用", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    AgentErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
