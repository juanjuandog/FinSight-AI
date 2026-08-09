package com.finsight;

import com.finsight.application.RecommendationStrategyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RecommendationStrategyProperties.class)
public class FinSightApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinSightApplication.class, args);
    }
}
