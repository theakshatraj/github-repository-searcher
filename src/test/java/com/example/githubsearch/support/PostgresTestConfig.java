package com.example.githubsearch.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a throwaway PostgreSQL 16 container for tests.
 *
 * <p>{@code @ServiceConnection} makes Spring Boot derive {@code spring.datasource.*} from the running
 * container, so no test ever needs hard-coded connection properties. The container is started and
 * stopped by Spring Boot's Testcontainers lifecycle support, which is why no {@code @Testcontainers}
 * annotation is required.
 *
 * <p>Tests run against the same database engine as production, which matters because the design relies
 * on PostgreSQL-specific behaviour (native upsert, {@code LOWER(language)} index, null ordering).
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
