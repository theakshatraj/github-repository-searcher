package com.example.githubsearch.dto.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks down the JSON contract with GitHub, independently of any HTTP call.
 *
 * <p>Two rules matter more than the happy path:
 * <ul>
 *   <li>GitHub adds fields over time, so unknown properties must never break parsing.</li>
 *   <li>Numbers and timestamps are optional on the wire. They use wrapper types ({@code Integer},
 *       {@code Long}, {@code Boolean}, {@code Instant}) so "GitHub did not send it" stays
 *       {@code null} instead of silently becoming {@code 0} or failing to unbox.</li>
 * </ul>
 *
 * <p>{@code @JsonProperty} is what maps GitHub's snake_case names to idiomatic Java names; without it
 * {@code stargazers_count} would be ignored and the field would always be null.
 */
class GithubDtoDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void mapsEveryFieldWeUseFromGithubsSnakeCaseJson() throws IOException {
        GithubSearchResponse response = read("search-ok.json");

        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.incompleteResults()).isFalse();
        assertThat(response.items()).hasSize(2);

        GithubRepositoryItem item = response.items().get(0);
        assertThat(item.id()).isEqualTo(6296790L);
        assertThat(item.name()).isEqualTo("spring-boot");
        assertThat(item.description()).isEqualTo("Spring Boot helps you build applications");
        assertThat(item.owner()).isNotNull();
        assertThat(item.owner().login()).isEqualTo("spring-projects");
        assertThat(item.language()).isEqualTo("Java");
        assertThat(item.stars()).isEqualTo(75000);
        assertThat(item.forks()).isEqualTo(41000);
        assertThat(item.lastUpdated()).isEqualTo(Instant.parse("2026-11-10T09:12:00Z"));
        assertThat(item.htmlUrl()).isEqualTo("https://github.com/spring-projects/spring-boot");
        assertThat(item.openIssues()).isEqualTo(12);
        assertThat(item.createdAt()).isEqualTo(Instant.parse("2012-10-19T04:00:00Z"));
    }

    @Test
    void ignoresUnknownPropertiesSoNewGithubFieldsCannotBreakParsing() throws IOException {
        // search-ok.json deliberately carries node_id, full_name, watchers_count, pushed_at,
        // default_branch, score and nested owner fields that this application never reads.
        GithubSearchResponse response = read("search-ok.json");

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(1).name()).isEqualTo("tiny-notes");
    }

    @Test
    void preservesNullsForOptionalTextAndTimestamps() throws IOException {
        GithubRepositoryItem item = read("search-nullable.json").items().get(0);

        assertThat(item.description()).isNull();
        assertThat(item.language()).isNull();
        assertThat(item.lastUpdated()).isNull();
        assertThat(item.createdAt()).isNull();
    }

    @Test
    void leavesAnAbsentOpenIssuesCountNullInsteadOfInventingZero() throws IOException {
        GithubRepositoryItem item = read("search-nullable.json").items().get(0);

        assertThat(item.openIssues()).isNull();
    }

    private GithubSearchResponse read(String fileName) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + fileName);
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return mapper.readValue(json, GithubSearchResponse.class);
    }
}
