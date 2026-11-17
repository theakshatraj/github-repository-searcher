package com.example.githubsearch.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the outbound HTTP plumbing in isolation from the client that uses it.
 *
 * <p>The base URL is proven by sending a request through the builder and asserting the absolute URL the
 * mock server saw. The timeouts are proven from the objects Spring built, because they are the only
 * observable: a {@code JdkClientHttpRequestFactory} has no getter for its read timeout.
 *
 * <p>The point of this class is that no timeout is configured inside {@code GithubSearchClient}: tests bind
 * a plain builder to {@link MockRestServiceServer}, and that binding replaces the request factory. If the
 * client set its own factory afterwards it would silently disable the mock and hit the network.
 */
class RestClientConfigTest {

    private static final GithubProperties PROPERTIES = new GithubProperties(
            "", "https://api.github.com", Duration.ofSeconds(5), Duration.ofSeconds(10));

    @Test
    void appliesTheConfiguredBaseUrlSoRelativeRequestPathsResolve() {
        RestClient.Builder builder = new RestClientConfig()
                .githubRestClientBuilder(PROPERTIES, new RestClientBuilderConfigurer());

        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/search/repositories?q=spring"))
                .andRespond(withSuccess("{}", org.springframework.http.MediaType.APPLICATION_JSON));

        builder.build().get().uri("/search/repositories?q=spring").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void appliesTheConnectTimeoutToTheJdkHttpClient() {
        assertThat(RestClientConfig.httpClient(PROPERTIES).connectTimeout())
                .contains(Duration.ofSeconds(5));
    }

    @Test
    void appliesTheReadTimeoutToTheRequestFactory() {
        ClientHttpRequestFactory factory = RestClientConfig.requestFactory(PROPERTIES);

        assertThat(factory).isInstanceOf(JdkClientHttpRequestFactory.class);
        // JdkClientHttpRequestFactory exposes setReadTimeout but no getter, so read the field it stores.
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(Duration.ofSeconds(10));
    }
}
