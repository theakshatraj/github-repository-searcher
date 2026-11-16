package com.example.githubsearch;

import com.example.githubsearch.support.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks down the configuration contract that the rest of the project depends on.
 *
 * <p>These are not cosmetic assertions. Each value is a decision that would be expensive to get wrong:
 * schema ownership, connection safety, the public port, the actuator surface and the GitHub client's
 * reserved settings. Encoding them here means a later phase cannot silently change them.
 */
@SpringBootTest(classes = GithubRepositorySearcherApplication.class)
@Import(PostgresTestConfig.class)
class ConfigurationContractTest {

    @Autowired
    private Environment environment;

    @Test
    void flywayOwnsTheSchemaAndHibernateOnlyValidates() {
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void jpaDoesNotHoldAConnectionOpenForTheViewLayer() {
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    }

    @Test
    void jdbcBindsTimestampsInUtc() {
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone"))
                .isEqualTo("UTC");
    }

    @Test
    void serverPortComesFromThePortVariableWithADefault() {
        assertThat(environment.getProperty("server.port")).isEqualTo("8080");
    }

    @Test
    void onlyTheHealthEndpointIsExposed() {
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health");
    }

    @Test
    void githubSettingsAreReservedWithSafeDefaults() {
        assertThat(environment.getProperty("github.base-url")).isEqualTo("https://api.github.com");
        assertThat(environment.getProperty("github.token")).isEmpty();
        assertThat(environment.getProperty("github.connect-timeout")).isEqualTo("5s");
        assertThat(environment.getProperty("github.read-timeout")).isEqualTo("10s");
    }

    @Test
    void datasourceHasNonSecretLocalDefaults() {
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost:5432/github_searcher");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("postgres");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("postgres");
    }
}
