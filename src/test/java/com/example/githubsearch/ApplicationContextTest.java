package com.example.githubsearch;

import com.example.githubsearch.support.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the application's Spring context can start against a real PostgreSQL 16 database.
 *
 * <p>This is the Phase 0 smoke test: it catches broken auto-configuration, a missing JDBC driver,
 * a Flyway misconfiguration or an invalid {@code ddl-auto=validate} setting before any feature code
 * exists.
 */
@SpringBootTest(classes = GithubRepositorySearcherApplication.class)
@Import(PostgresTestConfig.class)
class ApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBean(GithubRepositorySearcherApplication.class)).isNotNull();
    }
}
