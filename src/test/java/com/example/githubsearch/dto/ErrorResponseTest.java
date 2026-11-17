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
 * Contract tests for the one error body every non-2xx response uses.
 *
 * <p>The shape is part of the API: clients parse {@code message} and {@code details} to show a useful
 * validation error. The tests also lock the optional field rule - {@code details} must disappear entirely
 * when there is nothing to say, rather than forcing clients to handle {@code null} and {@code []}.
 */
class ErrorResponseTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void serializesTheStandardErrorBodyInBlueprintOrder() {
        ErrorResponse response = new ErrorResponse(
                Instant.parse("2026-11-16T10:30:00Z"),
                400,
                "Bad Request",
                "query must not be blank",
                "/api/github/search",
                List.of(new ErrorResponse.FieldErrorDetail("query", "query must not be blank")));

        JsonNode json = objectMapper.valueToTree(response);

        assertThat(fieldNamesOf(json))
                .containsExactly("timestamp", "status", "error", "message", "path", "details");
        assertThat(json.get("timestamp").asText()).isEqualTo("2026-11-16T10:30:00Z");
        assertThat(json.get("status").asInt()).isEqualTo(400);
        assertThat(json.get("error").asText()).isEqualTo("Bad Request");
        assertThat(json.get("message").asText()).isEqualTo("query must not be blank");
        assertThat(json.get("path").asText()).isEqualTo("/api/github/search");
        assertThat(json.get("details")).hasSize(1);
        assertThat(json.get("details").get(0).get("field").asText()).isEqualTo("query");
        assertThat(json.get("details").get(0).get("message").asText())
                .isEqualTo("query must not be blank");
    }

    @Test
    void omitsDetailsWhenTheyAreNull() {
        ErrorResponse response = new ErrorResponse(
                Instant.parse("2026-11-16T10:30:00Z"), 404, "Not Found",
                "Repository with id 9 was not found", "/api/github/repositories/9", null);

        JsonNode json = objectMapper.valueToTree(response);

        assertThat(fieldNamesOf(json))
                .containsExactly("timestamp", "status", "error", "message", "path");
        assertThat(json.has("details")).isFalse();
    }

    @Test
    void omitsDetailsWhenTheyAreEmpty() {
        ErrorResponse response = new ErrorResponse(
                Instant.parse("2026-11-16T10:30:00Z"), 502, "Bad Gateway",
                "GitHub API is currently unavailable", "/api/github/search", List.of());

        JsonNode json = objectMapper.valueToTree(response);

        assertThat(fieldNamesOf(json))
                .containsExactly("timestamp", "status", "error", "message", "path");
        assertThat(json.has("details")).isFalse();
    }

    private static List<String> fieldNamesOf(JsonNode json) {
        List<String> names = new ArrayList<>();
        ((ObjectNode) json).fieldNames().forEachRemaining(names::add);
        return names;
    }
}
