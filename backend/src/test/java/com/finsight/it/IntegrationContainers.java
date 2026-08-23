package com.finsight.it;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

final class IntegrationContainers {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("finsight_it")
            .withUsername("finsight")
            .withPassword("finsight");

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3-management")
    )
            .withUser("finsight", "finsight")
            .withPermission("/", "finsight", ".*", ".*", ".*");

    private IntegrationContainers() {
    }

    static synchronized void startPostgres() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    static synchronized void startRedis() {
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
    }

    static synchronized void startRabbit() {
        if (!RABBITMQ.isRunning()) {
            RABBITMQ.start();
        }
    }
}
