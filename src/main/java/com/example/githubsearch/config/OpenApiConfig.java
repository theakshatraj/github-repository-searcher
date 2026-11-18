package com.example.githubsearch.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API in the OpenAPI document served at {@code /v3/api-docs} and rendered by Swagger UI.
 *
 * <p>The title, version and description cannot be inferred from the controllers, so they are defined once
 * here. Springdoc merges this {@link OpenAPI} bean with the paths and schemas it generates from the
 * controller; defining the bean does not replace that generated content.
 *
 * <p>The version is the project version from {@code pom.xml}, not a separate API version. Keeping the two
 * equal avoids a Swagger page that claims a release the build did not produce.
 */
@Configuration
public class OpenApiConfig {

    private static final String API_TITLE = "GitHub Repository Searcher API";
    private static final String API_VERSION = "0.0.1-SNAPSHOT";
    private static final String API_DESCRIPTION = """
            Searches GitHub repositories, stores them in PostgreSQL without duplicates, and serves the \
            stored data with filters and sorting. The search endpoint is the only one that calls GitHub; \
            the read endpoints query PostgreSQL only. Every error uses the same JSON body.""";

    @Bean
    public OpenAPI githubRepositorySearcherOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title(API_TITLE)
                        .version(API_VERSION)
                        .description(API_DESCRIPTION));
    }
}
