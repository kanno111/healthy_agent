package com.healthy.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.healthy.agent.config.HealthyApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.Map;

@Component
public class HealthyApiClient {
    private static final Logger log = LoggerFactory.getLogger(HealthyApiClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public HealthyApiClient(
            RestClient.Builder builder,
            HealthyApiProperties properties,
            ObjectMapper objectMapper
    ) {
        this(createRestClient(builder, properties), objectMapper);
    }

    HealthyApiClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public ToolExecutionResult get(String uri, String authorization) {
        return get(uri, Map.of(), authorization);
    }

    public ToolExecutionResult get(
            String path,
            Map<String, ?> queryParameters,
            String authorization
    ) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring("Bearer ".length()).isBlank()) {
            return failure(401, 40100, "未登录或登录已失效");
        }
        return exchange(restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path(path);
                        queryParameters.forEach((name, value) -> builder.queryParam(name, value));
                        return builder.build();
                    })
                    .header(HttpHeaders.AUTHORIZATION, authorization));
    }

    public ToolExecutionResult patch(String path, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring("Bearer ".length()).isBlank()) {
            return failure(401, 40100, "未登录或登录已失效");
        }
        return exchange(restClient.patch()
                .uri(uriBuilder -> uriBuilder.path(path).build())
                .header(HttpHeaders.AUTHORIZATION, authorization));
    }

    public ToolExecutionResult post(
            String path,
            Object body,
            String authorization
    ) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring("Bearer ".length()).isBlank()) {
            return failure(401, 40100, "未登录或登录已失效");
        }
        return exchange(restClient.post()
                .uri(uriBuilder -> uriBuilder.path(path).build())
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .body(body));
    }

    public ToolExecutionResult post(String path, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring("Bearer ".length()).isBlank()) {
            return failure(401, 40100, "未登录或登录已失效");
        }
        return exchange(restClient.post()
                .uri(uriBuilder -> uriBuilder.path(path).build())
                .header(HttpHeaders.AUTHORIZATION, authorization));
    }

    private ToolExecutionResult exchange(RestClient.RequestHeadersSpec<?> request) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = request.retrieve().body(Map.class);
            if (envelope == null) {
                return dependencyUnavailable();
            }
            Object rawCode = envelope.get("code");
            int code = rawCode instanceof Number number ? number.intValue() : -1;
            if (code != 0) {
                return failure(200, code < 0 ? 50000 : code,
                        safeMessage(envelope.get("message") instanceof String message ? message : null));
            }
            Object data = envelope.get("data");
            return ToolExecutionResult.success(
                    data == null ? NullNode.getInstance() : objectMapper.valueToTree(data));
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            ErrorEnvelope body = readError(exception.getResponseBodyAsString());
            int code = body == null || body.code() == 0 ? defaultCode(status) : body.code();
            String message = body == null ? defaultMessage(status) : safeMessage(body.message());
            return failure(status, code, message);
        } catch (ResourceAccessException exception) {
            log.warn("Healthy API request failed: {}", exception.getClass().getSimpleName());
            return dependencyUnavailable();
        } catch (RuntimeException exception) {
            log.warn("Healthy API response could not be processed: {}",
                    exception.getClass().getSimpleName());
            return dependencyUnavailable();
        }
    }

    private ErrorEnvelope readError(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, ErrorEnvelope.class);
        } catch (RuntimeException | java.io.IOException ignored) {
            return null;
        }
    }

    private ToolExecutionResult dependencyUnavailable() {
        return ToolExecutionResult.failure(new ToolError(
                503, 50300, "DEPENDENCY_UNAVAILABLE", "医院业务服务暂时不可用", true));
    }

    private ToolExecutionResult failure(int httpStatus, int code, String message) {
        return ToolExecutionResult.failure(new ToolError(
                httpStatus, code, errorType(httpStatus, code), message,
                httpStatus == 429 || httpStatus == 503));
    }

    private String errorType(int httpStatus, int code) {
        if (httpStatus == 401 || code == 40100 || code == 40101) return "AUTH_REQUIRED";
        if (httpStatus == 403 || code == 40300) return "FORBIDDEN";
        if (httpStatus == 404 || code == 40400) return "RESOURCE_NOT_FOUND";
        if (httpStatus == 409 || code == 40900) return "BUSINESS_STATE_CHANGED";
        if (httpStatus == 429 || code == 42900) return "RATE_LIMITED";
        if (httpStatus == 503 || code == 50300) return "DEPENDENCY_UNAVAILABLE";
        if (httpStatus >= 500 || code == 50000) return "INTERNAL_FAILURE";
        return "INVALID_ARGUMENT";
    }

    private int defaultCode(int status) {
        return switch (status) {
            case 401 -> 40100;
            case 403 -> 40300;
            case 404 -> 40400;
            case 409 -> 40900;
            case 429 -> 42900;
            case 503 -> 50300;
            default -> status >= 500 ? 50000 : 40001;
        };
    }

    private String defaultMessage(int status) {
        return switch (status) {
            case 401 -> "登录已失效，请重新登录";
            case 403 -> "当前账号无权执行该查询";
            case 404 -> "查询的资源不存在";
            case 409 -> "资源状态已经变化";
            case 429 -> "请求过于频繁，请稍后再试";
            default -> status >= 500 ? "医院业务服务暂时不可用" : "查询参数无效";
        };
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "医院业务请求失败" : message.strip();
    }

    private static RestClient createRestClient(
            RestClient.Builder builder,
            HealthyApiProperties properties
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    private record ErrorEnvelope(int code, String message) {
    }
}
