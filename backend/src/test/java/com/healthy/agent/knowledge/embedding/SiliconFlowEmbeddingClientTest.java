package com.healthy.agent.knowledge.embedding;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SiliconFlowEmbeddingClientTest {

    @Test
    void returnsVectorsInProviderIndexOrder() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://embedding.example/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SiliconFlowEmbeddingClient client = new SiliconFlowEmbeddingClient(builder.build(), properties());
        String first = vectorJson(0.1f);
        String second = vectorJson(0.2f);

        server.expect(requestTo("https://embedding.example/v1/embeddings"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("BAAI/bge-m3"))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess("""
                        {"model":"BAAI/bge-m3","data":[
                          {"index":1,"embedding":%s},
                          {"index":0,"embedding":%s}
                        ]}
                        """.formatted(second, first), MediaType.APPLICATION_JSON));

        EmbeddingBatch result = client.embed(java.util.List.of("第一段", "第二段"));

        assertThat(result.model()).isEqualTo("BAAI/bge-m3");
        assertThat(result.vectors()).hasSize(2);
        assertThat(result.vectors().get(0)).hasSize(1024).first().isEqualTo(0.1f);
        assertThat(result.vectors().get(1)).hasSize(1024).first().isEqualTo(0.2f);
        server.verify();
    }

    @Test
    void rejectsUnexpectedVectorDimensions() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://embedding.example/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SiliconFlowEmbeddingClient client = new SiliconFlowEmbeddingClient(builder.build(), properties());

        server.expect(requestTo("https://embedding.example/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"model":"BAAI/bge-m3","data":[{"index":0,"embedding":[0.1,0.2]}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed(java.util.List.of("文本")))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.EMBEDDING_UNAVAILABLE));
        server.verify();
    }

    @Test
    void doesNotExposeApiKeyInPropertiesToString() {
        assertThat(properties().toString())
                .doesNotContain("test-key")
                .contains("<redacted>");
    }

    private EmbeddingProperties properties() {
        return new EmbeddingProperties(
                "https://embedding.example/v1", "test-key", "BAAI/bge-m3",
                1024, 16, Duration.ofSeconds(5), Duration.ofSeconds(60)
        );
    }

    private String vectorJson(float value) {
        return "[" + String.join(",", Collections.nCopies(1024, Float.toString(value))) + "]";
    }
}
