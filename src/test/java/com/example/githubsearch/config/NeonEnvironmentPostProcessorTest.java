package com.example.githubsearch.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the Neon URL conversion without starting Spring, a web server or a database.
 *
 * <p>The failure mode this prevents is subtle: the application would start with the provider URI, the
 * JDBC driver would reject it, and the container would exit with a message that does not mention the
 * format. The credentials here are fake; no real Neon password is stored in the repository.
 */
class NeonEnvironmentPostProcessorTest {

    private final NeonEnvironmentPostProcessor processor = new NeonEnvironmentPostProcessor();

    @Test
    void convertsPooledAndDirectUrlsAndDecodesCredentials() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DATABASE_URL",
                        "postgresql://neondb_owner:p%40ss%3Aword+plus@ep-pooler.example.neon.tech:5432/neondb"
                                + "?channel_binding=require&sslmode=require")
                .withProperty("DATABASE_URL_UNPOOLED",
                        "postgresql://neondb_owner:p%40ss%3Aword+plus@ep-direct.example.neon.tech:5432/neondb"
                                + "?channel_binding=require&sslmode=require");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://ep-pooler.example.neon.tech:5432/neondb?sslmode=require");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("neondb_owner");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("p@ss:word+plus");

        assertThat(environment.getProperty("spring.flyway.url"))
                .isEqualTo("jdbc:postgresql://ep-direct.example.neon.tech:5432/neondb?sslmode=require");
        assertThat(environment.getProperty("spring.flyway.user")).isEqualTo("neondb_owner");
        assertThat(environment.getProperty("spring.flyway.password")).isEqualTo("p@ss:word+plus");
    }

    @Test
    void explicitDbUrlWinsSoExistingSetupsAreUntouched() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:postgresql://localhost:5432/local")
                .withProperty("DATABASE_URL", "postgresql://user:secret@ep.example.neon.tech/neondb");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.datasource.url")).isNull();
        assertThat(environment.getProperty("spring.flyway.url")).isNull();
    }

    @Test
    void doesNothingWhenNoNeonUrlIsPresent() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.datasource.url")).isNull();
    }

    @Test
    void fallsBackToTheDirectUrlAndTheStandardPortWhenOnlyOneIsSupplied() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DATABASE_URL_UNPOOLED",
                        "postgresql://neondb_owner:secret@ep-direct.example.neon.tech/neondb");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://ep-direct.example.neon.tech:5432/neondb?sslmode=require");
        assertThat(environment.getProperty("spring.flyway.url"))
                .isEqualTo("jdbc:postgresql://ep-direct.example.neon.tech:5432/neondb?sslmode=require");
    }

    @Test
    void acceptsQuotedValuesWrittenByEnvFiles() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DATABASE_URL",
                        "\"postgresql://neondb_owner:secret@ep.example.neon.tech/neondb?sslmode=require\"");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://ep.example.neon.tech:5432/neondb?sslmode=require");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("secret");
    }

    @Test
    void rejectsANonPostgresUrlWithANamedVariable() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DATABASE_URL", "mysql://user:secret@host/database");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_URL")
                .hasMessageContaining("postgres")
                .hasMessageNotContaining("secret");
    }

    @Test
    void neverPutsTheRawValueInAnErrorMessage() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DATABASE_URL", "not a url with secret");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DATABASE_URL is not a valid URL")
                .hasMessageNotContaining("secret");
    }
}
