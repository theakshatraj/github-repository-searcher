package com.example.githubsearch.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * One repository inside GitHub's search results.
 *
 * <p>This is the <em>wire</em> shape, not our database row and not our public API shape. Keeping it
 * separate means GitHub can rename or add fields without changing the entity, and our API can choose its
 * own names without being dictated by GitHub.
 *
 * <p>The Java field names are idiomatic camelCase; {@code @JsonProperty} supplies GitHub's snake_case
 * names. {@code updated_at}/{@code created_at} are ISO-8601 timestamps, parsed straight into
 * {@link Instant} by Jackson's Java time module - no manual string parsing.
 *
 * <p>Why {@code Integer}/{@code Long} rather than {@code int}/{@code long}: GitHub may omit a count. A
 * primitive would silently become 0, which is indistinguishable from a real zero; a wrapper stays
 * {@code null} and the mapper decides what the database default should be.
 *
 * @param id          GitHub's permanent repository id; becomes our primary key
 * @param name        repository name, e.g. {@code spring-boot}
 * @param description optional summary; often null on GitHub
 * @param owner       the owning user or organisation; its {@code login} is what we store
 * @param language    primary language; null when GitHub could not detect one
 * @param stars       from {@code stargazers_count}
 * @param forks       from {@code forks_count}
 * @param lastUpdated from {@code updated_at}
 * @param htmlUrl     the web URL of the repository
 * @param openIssues  from {@code open_issues_count}
 * @param createdAt   from {@code created_at}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubRepositoryItem(
        @JsonProperty("id") Long id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("owner") GithubOwner owner,
        @JsonProperty("language") String language,
        @JsonProperty("stargazers_count") Integer stars,
        @JsonProperty("forks_count") Integer forks,
        @JsonProperty("updated_at") Instant lastUpdated,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("open_issues_count") Integer openIssues,
        @JsonProperty("created_at") Instant createdAt) {
}
