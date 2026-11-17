package com.example.githubsearch.dto;

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
        Long id,
        String name,
        String description,
        String owner,
        String language,
        Integer stars,
        Integer forks,
        Instant lastUpdated,
        String htmlUrl,
        Integer openIssues,
        Instant createdAt) {
}
