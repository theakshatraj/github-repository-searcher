package com.example.githubsearch.config;

import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Builds the one outbound HTTP client this application needs.
 *
 * <p>Why the client is a bean here instead of being constructed inside {@code GithubSearchClient}:
 * <ul>
 *   <li>Timeouts are networking policy, not business behaviour. Keeping them here means the client class
 *       has one job and the policy can change without touching it.</li>
 *   <li>Tests need to replace the request factory with {@code MockRestServiceServer}. If the client created
 *       its own factory after receiving the builder, it would overwrite the mock and the tests would try
 *       to reach the real GitHub.</li>
 * </ul>
 *
 * <p>{@link RestClientBuilderConfigurer} applies the same defaults Spring Boot's own auto-configured
 * builder would (notably the application's Jackson {@code HttpMessageConverters}, which know how to parse
 * {@code Instant}). It is applied <b>before</b> the base URL and factory below, so our explicit settings
 * always win. Because this bean exists, Boot's own {@code RestClient.Builder} backs off
 * ({@code @ConditionalOnMissingBean}), leaving exactly one candidate for injection.
 */
@Configuration(proxyBeanMethods = false)
public class RestClientConfig {

    @Bean
    RestClient.Builder githubRestClientBuilder(GithubProperties properties,
                                               RestClientBuilderConfigurer bootConfigurer) {
        RestClient.Builder builder = bootConfigurer.configure(RestClient.builder());
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties));
    }

    /**
     * Creates the JDK HTTP client, which is what carries the connect timeout: the timeout belongs to the
     * {@link HttpClient}, not to any single request.
     */
    static HttpClient httpClient(GithubProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder();
        if (properties.connectTimeout() != null) {
            builder.connectTimeout(properties.connectTimeout());
        }
        return builder.build();
    }

    /**
     * Wraps the JDK client for Spring's {@link RestClient} and applies the read timeout, i.e. how long the
     * client waits for bytes after the request has been sent.
     */
    static ClientHttpRequestFactory requestFactory(GithubProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient(properties));
        if (properties.readTimeout() != null) {
            factory.setReadTimeout(properties.readTimeout());
        }
        return factory;
    }
}
