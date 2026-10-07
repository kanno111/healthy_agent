package com.healthy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.elasticsearch")
public record ElasticsearchProperties(String uri, String indexName) {
}
