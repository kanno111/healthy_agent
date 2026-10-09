package com.healthy.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {
    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    ObjectMapper legacyObjectMapper() {
        // Elasticsearch and the current tool DTOs still use Jackson 2 types,
        // while Spring Boot 4's managed mapper uses Jackson 3.
        return new ObjectMapper().findAndRegisterModules();
    }
}
