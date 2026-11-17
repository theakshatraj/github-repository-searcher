package com.example.githubsearch.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the typed GitHub configuration really binds from properties.
 *
 * <p>Uses {@link ApplicationContextRunner}, so no web server and no database are started: it proves the
 * binding contract in milliseconds. The values tested here are the ones {@code application.yml} already
 * reserves, so a typo in either side fails this test instead of failing at runtime with a null token or a
 * zero timeout.
 */
class GithubPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues(
                    "github.token=secret-token",
                    "github.base-url=https://api.github.com",
                    "github.connect-timeout=5s",
                    "github.read-timeout=10s");

    @Test
    void bindsEveryConfiguredValue() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GithubProperties.class);

            GithubProperties properties = context.getBean(GithubProperties.class);
            assertThat(properties.token()).isEqualTo("secret-token");
            assertThat(properties.baseUrl()).isEqualTo("https://api.github.com");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void allowsAnEmptyTokenBecauseUnauthenticatedSearchIsSupported() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues(
                        "github.token=",
                        "github.base-url=https://api.github.com",
                        "github.connect-timeout=5s",
                        "github.read-timeout=10s")
                .run(context -> assertThat(context.getBean(GithubProperties.class).token()).isEmpty());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GithubProperties.class)
    static class PropertiesConfig {
    }
}
