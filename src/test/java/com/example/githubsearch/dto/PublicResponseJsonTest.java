package com.example.githubsearch.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the JSON our own API exposes.
 *
 * <p>The record component names <em>are</em> the public field names, and their declaration order is the
 * order Jackson writes. Those names are part of the API contract (the assignment and the README examples
 * use {@code htmlUrl}, {@code openIssues}, {@code createdAt}), so a rename or a reordering should fail a
 * test rather than silently break clients.
 *
 * <p>No controller is involved: this fixes the DTO layer now, and Phase 4 only has to add HTTP.
 */
class PublicResponseJsonTest {

    private static final Instant LAST_UPDATED = Instant.parse("2026-11-10T09:12:00Z");
    private static final Instant CREATED_AT = Instant.parse("2012-10-19T04:00:00Z");

    /**
     * Mirrors Spring Boot's auto-configured mapper closely enough for these assertions: modules registered
     * (so {@link Instant} works) and timestamps written as ISO-8601 strings, not epoch numbers.
     */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void repositoryResponseKeepsTheCoreFieldsFirstAndAppendsTheExtraOnes() {
        RepositoryResponse response = sampleRepository();

        JsonNode json = objectMapper.valueToTree(response);

        assertThat(fieldNamesOf(json)).containsExactly(
                "id", "name", "description", "owner", "language", "stars", "forks", "lastUpdated",
                "htmlUrl", "openIssues", "createdAt");
        assertThat(json.get("lastUpdated").asText()).isEqualTo("2026-11-10T09:12:00Z");
        assertThat(json.get("createdAt").asText()).isEqualTo("2012-10-19T04:00:00Z");
        assertThat(json.get("htmlUrl").asText())
                .isEqualTo("https://github.com/spring-projects/spring-boot");
        assertThat(json.get("openIssues").asInt()).isEqualTo(12);
    }

    @Test
    void searchResponseWrapsAMessageAndTheRepositories() {
        SearchResponse response = new SearchResponse("Repositories fetched and saved successfully",
                List.of(sampleRepository()));

        JsonNode json = objectMapper.valueToTree(response);

        assertThat(fieldNamesOf(json)).containsExactly("message", "repositories");
        assertThat(json.get("repositories")).hasSize(1);
    }

    @Test
    void repositoryPageResponseAddsPagingMetadataAfterTheRepositories() {
        RepositoryPageResponse response = new RepositoryPageResponse(
                List.of(sampleRepository()), 2, 25, 51L, 3);

        JsonNode json = objectMapper.valueToTree(response);

        assertThat(fieldNamesOf(json))
                .containsExactly("repositories", "page", "size", "totalElements", "totalPages");
        assertThat(json.get("page").asInt()).isEqualTo(2);
        assertThat(json.get("size").asInt()).isEqualTo(25);
        assertThat(json.get("totalElements").asLong()).isEqualTo(51L);
        assertThat(json.get("totalPages").asInt()).isEqualTo(3);
    }

    private static RepositoryResponse sampleRepository() {
        return new RepositoryResponse(
                6296790L, "spring-boot", "Spring Boot helps you build applications",
                "spring-projects", "Java", 75000, 41000, LAST_UPDATED,
                "https://github.com/spring-projects/spring-boot", 12, CREATED_AT);
    }

    private static List<String> fieldNamesOf(JsonNode json) {
        List<String> names = new ArrayList<>();
        ((ObjectNode) json).fieldNames().forEachRemaining(names::add);
        return names;
    }
}
