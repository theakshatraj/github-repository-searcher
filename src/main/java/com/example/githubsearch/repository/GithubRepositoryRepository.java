package com.example.githubsearch.repository;

import com.example.githubsearch.entity.GithubRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Data-access port for stored repositories.
 *
 * <p>Spring Data generates the implementation at startup from this interface, which is the Repository
 * pattern for free: no boilerplate DAO class, and the query surface is small and explicit.
 *
 * <p>{@link JpaSpecificationExecutor} adds {@code findAll(Specification, Pageable)}, which is how optional
 * filters are built without string-concatenated SQL.
 */
public interface GithubRepositoryRepository
        extends JpaRepository<GithubRepository, Long>, JpaSpecificationExecutor<GithubRepository> {

    /**
     * Inserts a repository, or updates it when the id already exists - in one atomic statement.
     *
     * <p>Why this instead of {@code save()}: the id is assigned by GitHub, so Spring Data cannot tell a new
     * entity from an existing one and would run SELECT-then-INSERT-or-UPDATE. Two concurrent requests could
     * both see "no row" and both insert; one would fail. {@code ON CONFLICT (id) DO UPDATE} lets PostgreSQL
     * decide, so there is no race and no read-then-write window. It also matches the assignment wording
     * ("update the existing record instead of creating a duplicate") one-to-one.
     *
     * <p>{@code EXCLUDED} is the pseudo-table holding the row that was proposed for insertion, so
     * {@code stars = EXCLUDED.stars} means "overwrite with the incoming value". {@code synced_at} is always
     * refreshed from the database clock, never from the caller.
     *
     * <p>{@code flushAutomatically} pushes pending changes first so the upsert sees the latest state;
     * {@code clearAutomatically} empties the persistence context afterwards, because native SQL bypasses
     * Hibernate and any cached entity would otherwise be stale.
     *
     * <p>This method is transactional on its own so it works both standalone (tests) and joined to a larger
     * transaction (the service's batch, where one failing row rolls back the whole batch).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            INSERT INTO github_repositories
                (id, name, description, owner, language, stars, forks, last_updated, html_url,
                 open_issues_count, created_at, synced_at)
            VALUES
                (:id, :name, :description, :owner, :language, :stars, :forks, :lastUpdated, :htmlUrl,
                 :openIssues, :createdAt, now())
            ON CONFLICT (id) DO UPDATE SET
                name              = EXCLUDED.name,
                description       = EXCLUDED.description,
                owner             = EXCLUDED.owner,
                language          = EXCLUDED.language,
                stars             = EXCLUDED.stars,
                forks             = EXCLUDED.forks,
                last_updated      = EXCLUDED.last_updated,
                html_url          = EXCLUDED.html_url,
                open_issues_count = EXCLUDED.open_issues_count,
                created_at        = EXCLUDED.created_at,
                synced_at         = now()
            """, nativeQuery = true)
    void upsert(@Param("id") Long id,
                @Param("name") String name,
                @Param("description") String description,
                @Param("owner") String owner,
                @Param("language") String language,
                @Param("stars") Integer stars,
                @Param("forks") Integer forks,
                @Param("lastUpdated") Instant lastUpdated,
                @Param("htmlUrl") String htmlUrl,
                @Param("openIssues") Integer openIssues,
                @Param("createdAt") Instant createdAt);
}
