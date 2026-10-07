package com.healthy.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HealthyAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(HealthyAgentApplication.class, args);
    }
}
