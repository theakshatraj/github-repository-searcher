package com.example.githubsearch;

import com.example.githubsearch.support.PostgresTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that springdoc is wired up: the machine-readable OpenAPI document and the Swagger UI are both served.
 *
 * <p>Swagger UI is a mandatory deliverable, and it is only useful if the JSON document behind it is valid.
 * Asserting the document here means a broken springdoc/Spring Boot combination fails the build instead of
 * being discovered by a reviewer.
 */
@SpringBootTest(classes = GithubRepositorySearcherApplication.class)
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class OpenApiDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void openApiDocumentIsServed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").exists());
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void openApiDocumentCarriesTheConfiguredApiInformationAndEveryPath() throws Exception {
        JsonNode document = openApiDocument();

        assertThat(document.at("/info/title").asText()).isEqualTo("GitHub Repository Searcher API");
        assertThat(document.at("/info/version").asText()).isEqualTo("0.0.1-SNAPSHOT");
        assertThat(document.at("/info/description").asText()).isNotBlank();

        assertThat(document.at("/paths").has("/api/github/search")).isTrue();
        assertThat(document.at("/paths").has("/api/github/repositories")).isTrue();
        assertThat(document.at("/paths").has("/api/github/repositories/{id}")).isTrue();
    }

    @Test
    void searchOperationDocumentsItsStatusesAndRequestExamples() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode post = document.at("/paths/~1api~1github~1search/post");

        assertThat(post.at("/tags/0").asText()).isEqualTo("GitHub repositories");
        assertThat(post.at("/summary").asText()).isNotBlank();
        assertThat(post.at("/description").asText()).isNotBlank();
        assertThat(post.at("/requestBody/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/SearchRequest");

        JsonNode responses = post.path("responses");
        assertThat(responses.has("200")).isTrue();
        assertThat(responses.has("400")).isTrue();
        assertThat(responses.has("429")).isTrue();
        assertThat(responses.has("502")).isTrue();
        assertThat(responses.has("503")).isTrue();
        assertThat(responses.has("504")).isTrue();
        assertThat(responses.has("500")).isTrue();

        assertThat(responses.at("/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/SearchResponse");
        assertThat(responses.at("/400/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ErrorResponse");
        assertThat(responses.at("/429/headers/Retry-After").isMissingNode()).isFalse();

        JsonNode requestProperties = document.at("/components/schemas/SearchRequest/properties");
        assertThat(requestProperties.at("/query/example").asText()).isEqualTo("spring boot");
        assertThat(requestProperties.at("/language/example").asText()).isEqualTo("Java");
        assertThat(requestProperties.at("/sort/example").asText()).isEqualTo("stars");
        assertThat(requestProperties.has("withinGithubResultCap")).isFalse();
    }

    @Test
    void repositoryOperationsDocumentTheirParametersAndStatuses() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode get = document.at("/paths/~1api~1github~1repositories/get");

        Map<String, JsonNode> parameters = parametersByName(get);
        assertThat(parameters).containsKeys("language", "minStars", "sort", "order", "page", "size");
        assertThat(parameters.get("language").at("/description").asText()).contains("Case-insensitive");
        assertThat(parameters.get("minStars").at("/schema/type").asText()).isEqualTo("integer");
        assertThat(parameters.get("sort").at("/schema/enum").toString())
                .isEqualTo("[\"stars\",\"forks\",\"updated\"]");
        assertThat(parameters.get("order").at("/schema/enum").toString())
                .isEqualTo("[\"asc\",\"desc\"]");

        JsonNode listResponses = get.path("responses");
        assertThat(listResponses.has("200")).isTrue();
        assertThat(listResponses.has("400")).isTrue();
        assertThat(listResponses.has("503")).isTrue();
        assertThat(listResponses.has("500")).isTrue();
        assertThat(listResponses.at("/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/RepositoryPageResponse");

        JsonNode byId = document.at("/paths/~1api~1github~1repositories~1{id}/get");
        assertThat(byId.at("/parameters/0/name").asText()).isEqualTo("id");
        assertThat(byId.at("/parameters/0/description").asText()).isNotBlank();

        JsonNode byIdResponses = byId.path("responses");
        assertThat(byIdResponses.has("200")).isTrue();
        assertThat(byIdResponses.has("400")).isTrue();
        assertThat(byIdResponses.has("404")).isTrue();
        assertThat(byIdResponses.has("503")).isTrue();
        assertThat(byIdResponses.has("500")).isTrue();
        assertThat(byIdResponses.at("/404/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ErrorResponse");
    }

    @Test
    void publicSchemasCarryExamplesAndHideTheDerivedValidationProperty() throws Exception {
        JsonNode schemas = openApiDocument().at("/components/schemas");

        assertThat(schemas.at("/RepositoryResponse/properties/id/example").asText()).isEqualTo("123456");
        assertThat(schemas.at("/RepositoryResponse/properties/name/example").asText())
                .isEqualTo("spring-boot-example");
        assertThat(schemas.at("/RepositoryResponse/properties/htmlUrl/example").asText())
                .isEqualTo("https://github.com/user123/spring-boot-example");
        assertThat(schemas.at("/SearchResponse/properties/message/example").asText())
                .isEqualTo("Repositories fetched and saved successfully");
        assertThat(schemas.at("/RepositoryPageResponse/properties/size/example").asText()).isEqualTo("100");
        assertThat(schemas.at("/ErrorResponse/properties/status/example").asText()).isEqualTo("400");
        assertThat(schemas.at("/ErrorResponse/properties/details/description").asText()).isNotBlank();
    }

    private JsonNode openApiDocument() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private static Map<String, JsonNode> parametersByName(JsonNode operation) {
        Map<String, JsonNode> byName = new HashMap<>();
        operation.path("parameters").forEach(parameter ->
                byName.put(parameter.path("name").asText(), parameter));
        return byName;
    }
}
