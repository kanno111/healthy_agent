package com.healthy.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.config.HealthyApiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HealthyApiClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void forwardsAuthorizationAndUnwrapsHealthyResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://healthy.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HealthyApiClient client = new HealthyApiClient(builder.build(), objectMapper);

        server.expect(requestTo("https://healthy.example/api/user/appointments"))
                .andExpect(header("Authorization", "Bearer patient-jwt"))
                .andRespond(withSuccess("""
                        {"code":0,"message":"success","data":[{"id":201,"status":"BOOKED"}]}
                        """, MediaType.APPLICATION_JSON));

        ToolExecutionResult result = client.get(
                "/api/user/appointments", "Bearer patient-jwt");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get(0).get("id").asLong()).isEqualTo(201);
        server.verify();
    }

    @Test
    void mapsExpiredJwtWithoutExposingIt() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://healthy.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HealthyApiClient client = new HealthyApiClient(builder.build(), objectMapper);

        server.expect(requestTo("https://healthy.example/api/user/waitlists"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":40100,\"message\":\"登录已失效\",\"data\":null}"));

        ToolExecutionResult result = client.get(
                "/api/user/waitlists", "Bearer expired-secret-token");

        assertThat(result.ok()).isFalse();
        assertThat(result.error().type()).isEqualTo("AUTH_REQUIRED");
        assertThat(result.toString()).doesNotContain("expired-secret-token");
        server.verify();
    }

    @Test
    void encodesChineseQueryParameterExactlyOnce() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://healthy.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HealthyApiClient client = new HealthyApiClient(builder.build(), objectMapper);

        server.expect(requestTo(
                        "https://healthy.example/api/user/doctors?page=1&pageSize=10&keyword=%E9%99%88%E4%B9%A6%E5%AE%81"))
                .andExpect(header("Authorization", "Bearer patient-jwt"))
                .andRespond(withSuccess("""
                        {"code":0,"message":"success","data":{"records":[{"id":2,"name":"陈书宁"}],"total":1}}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", 1);
        query.put("pageSize", 10);
        query.put("keyword", "陈书宁");
        ToolExecutionResult result = client.get(
                "/api/user/doctors",
                query,
                "Bearer patient-jwt");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().path("total").asInt()).isEqualTo(1);
        assertThat(result.data().path("records").get(0).path("name").asText())
                .isEqualTo("陈书宁");
        server.verify();
    }

    @Test
    void sendsCancellationAsPatchWithAuthorization() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://healthy.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HealthyApiClient client = new HealthyApiClient(builder.build(), objectMapper);

        server.expect(requestTo("https://healthy.example/api/user/appointments/201/cancel"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer patient-jwt"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"message\":\"success\",\"data\":null}",
                        MediaType.APPLICATION_JSON));

        ToolExecutionResult result = client.patch(
                "/api/user/appointments/201/cancel", "Bearer patient-jwt");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().isNull()).isTrue();
        server.verify();
    }

    @Test
    void sendsAppointmentCreationAsPostWithAuthorizationAndRequestId() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://healthy.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HealthyApiClient client = new HealthyApiClient(builder.build(), objectMapper);

        server.expect(requestTo("https://healthy.example/api/user/appointments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer patient-jwt"))
                .andExpect(content().json(
                        "{\"scheduleSlotId\":101,\"requestId\":\"request-101\"}"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"message\":\"success\",\"data\":{\"id\":201}}",
                        MediaType.APPLICATION_JSON));

        ToolExecutionResult result = client.post(
                "/api/user/appointments",
                Map.of("scheduleSlotId", 101L, "requestId", "request-101"),
                "Bearer patient-jwt");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().path("id").asLong()).isEqualTo(201L);
        server.verify();
    }

    @Test
    void sendsBodylessWaitlistConfirmationPostWithAuthorization() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://healthy.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HealthyApiClient client = new HealthyApiClient(builder.build(), objectMapper);

        server.expect(requestTo("https://healthy.example/api/user/waitlists/301/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer patient-jwt"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"message\":\"success\",\"data\":null}",
                        MediaType.APPLICATION_JSON));

        ToolExecutionResult result = client.post(
                "/api/user/waitlists/301/confirm", "Bearer patient-jwt");

        assertThat(result.ok()).isTrue();
        server.verify();
    }

    @Test
    void realJdkHttpClientSupportsPatch() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/api/user/appointments/201/cancel", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"code\":0,\"message\":\"success\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HealthyApiProperties properties = new HealthyApiProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(2), Duration.ofSeconds(2));
            HealthyApiClient client = new HealthyApiClient(
                    RestClient.builder(), properties, objectMapper);

            ToolExecutionResult result = client.patch(
                    "/api/user/appointments/201/cancel", "Bearer patient-jwt");

            assertThat(result.ok()).isTrue();
            assertThat(method.get()).isEqualTo("PATCH");
            assertThat(authorization.get()).isEqualTo("Bearer patient-jwt");
        } finally {
            server.stop(0);
        }
    }
}
