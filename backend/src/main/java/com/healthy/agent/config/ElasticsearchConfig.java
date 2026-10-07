package com.healthy.agent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {
    @Bean(destroyMethod = "close")
    ElasticsearchClient elasticsearchClient(ElasticsearchProperties properties) {
        return ElasticsearchClient.of(builder -> builder.host(properties.uri()));
    }
}
