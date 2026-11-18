package com.example.githubsearch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One repository as our API returns it.
 *
 * <p>This is deliberately <em>not</em> the JPA {@code GithubRepository} entity. Exposing an entity would
 * couple the JSON contract to the table: renaming a column, adding a lazy association or an internal flag
 * would leak into the API, and Jackson would happily serialize whatever the database happens to hold. The
 * DTO is a small, explicit promise that survives schema changes and cannot expose {@code syncedAt} or
 * Hibernate internals.
 *
 * <p>Field order matters: the eight core assignment fields come first, then {@code htmlUrl},
 * {@code openIssues} and {@code createdAt}, matching the blueprint examples. Record component order is the
 * order Jackson writes, and {@code PublicResponseJsonTest} locks it.
 *
 * <p>{@link Instant} serializes as ISO-8601 UTC ({@code 2026-11-10T09:12:00Z}), which is unambiguous for
 * clients, unlike a local date-time.
 *
 * @param id          GitHub's repository id (also our primary key)
 * @param name        repository name
 * @param description optional summary; may be null
 * @param owner       owner login
 * @param language    primary language; may be null
 * @param stars       stargazers count; never null in storage
 * @param forks       forks count; never null in storage
 * @param lastUpdated GitHub's {@code updated_at}; may be null
 * @param htmlUrl     repository web URL; may be null
 * @param openIssues  open issue count; never null in storage
 * @param createdAt   GitHub's {@code created_at}; may be null
 */
public record RepositoryResponse(
        @Schema(description = "GitHub's repository id, also the primary key we store it under.",
                example = "123456")
        Long id,

        @Schema(description = "Repository name.", example = "spring-boot-example")
        String name,

        @Schema(description = "Repository summary; null when GitHub has none.",
                example = "A sample Spring Boot application")
        String description,

        @Schema(description = "Owner login, not the display name.", example = "user123")
        String owner,

        @Schema(description = "Primary language; null when GitHub reports none.", example = "Java")
        String language,

        @Schema(description = "Stargazers count.", example = "4200")
        Integer stars,

        @Schema(description = "Forks count.", example = "310")
        Integer forks,

        @Schema(description = "GitHub's updated_at timestamp in UTC.", example = "2026-11-10T09:12:00Z")
        Instant lastUpdated,

        @Schema(description = "Repository page on GitHub.",
                example = "https://github.com/user123/spring-boot-example")
        String htmlUrl,

        @Schema(description = "Open issue count.", example = "12")
        Integer openIssues,

        @Schema(description = "GitHub's created_at timestamp in UTC.", example = "2023-01-15T08:30:00Z")
        Instant createdAt) {
}
