package com.example.githubsearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed, immutable view of the {@code github.*} configuration.
 *
 * <p>Why a record instead of {@code @Value} fields: the whole GitHub configuration is one named, typed
 * object that can be injected anywhere, validated by the compiler, and compared in tests. {@code @Value}
 * would scatter three or four string keys across the classes that need them.
 *
 * <p>Why {@code @ConfigurationProperties} constructor binding: Spring Boot 3 binds a record through its
 * canonical constructor, so every value is final and the object cannot be half-initialised. Property names
 * are kebab-case in YAML ({@code connect-timeout}) and camelCase here ({@code connectTimeout}); Boot maps
 * them automatically.
 *
 * <p>The token is <b>optional</b>: GitHub allows unauthenticated search (about 10 requests per minute), so
 * the application must start with an empty token. It is never logged and never returned by an endpoint.
 *
 * <p>This class is registered once, by {@code @ConfigurationPropertiesScan} on the application class.
 * Nothing constructs it with {@code new} except tests.
 *
 * @param token          optional GitHub personal access token; empty means "call unauthenticated"
 * @param baseUrl        GitHub API root, e.g. {@code https://api.github.com}
 * @param connectTimeout how long the TCP/TLS connection may take to open
 * @param readTimeout    how long to wait for the response once the request is sent
 */
@ConfigurationProperties(prefix = "github")
public record GithubProperties(String token, String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
