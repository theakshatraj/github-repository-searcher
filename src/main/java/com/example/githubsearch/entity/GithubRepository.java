package com.example.githubsearch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.util.Objects;

/**
 * One GitHub repository as we store it.
 *
 * <p>Mapping notes:
 * <ul>
 *   <li><b>No {@code @GeneratedValue}.</b> GitHub assigns the id, so the application always supplies it.
 *       That is also why {@code save()} is not used for writes - with an assigned id Spring cannot tell
 *       "new" from "existing" and falls back to SELECT-then-INSERT-or-UPDATE, which is racy. The repository
 *       exposes a native {@code INSERT ... ON CONFLICT} instead.</li>
 *   <li><b>{@code syncedAt} is read-only.</b> {@code insertable = false, updatable = false} means the column
 *       is owned by the database default ({@code now()}) and by the upsert SQL. Hibernate never writes it,
 *       so the entity cannot fake a sync timestamp.</li>
 *   <li><b>{@code Instant} for timestamps.</b> A point in time, always UTC, no local-zone ambiguity. The
 *       database column is {@code TIMESTAMPTZ}.</li>
 * </ul>
 *
 * <p>This class is a mapping artefact: it has no business logic and must never be returned from a
 * controller. Services convert it to a DTO at the boundary.
 */
@Entity
@Table(name = "github_repositories")
public class GithubRepository {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "owner", nullable = false)
    private String owner;

    @Column(name = "language")
    private String language;

    @Column(name = "stars", nullable = false)
    private Integer stars;

    @Column(name = "forks", nullable = false)
    private Integer forks;

    @Column(name = "last_updated")
    private Instant lastUpdated;

    @Column(name = "html_url")
    private String htmlUrl;

    @Column(name = "open_issues_count", nullable = false)
    private Integer openIssues;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "synced_at", nullable = false, insertable = false, updatable = false)
    private Instant syncedAt;

    /**
     * Read-only sort key, computed by the database: {@code 0} when the repository has an update timestamp,
     * {@code 1} when it does not.
     *
     * <p>Why this exists: PostgreSQL's {@code ORDER BY last_updated DESC} puts {@code NULL}s <em>first</em>,
     * but a repository without an update timestamp should sort last. Spring Data's
     * {@code Sort.Order.nullsLast()} is not an option here - it fails with
     * {@code UnsupportedOperationException: Applying Null Precedence using Criteria Queries is not yet
     * supported} on the {@code Specification} path. Ordering by this flag first, then by the real column,
     * produces {@code ORDER BY (last_updated IS NULL) ASC, last_updated DESC} without any raw SQL in the
     * service layer.
     *
     * <p>It is a {@code @Formula}: a select-only expression, not a column, so it is invisible to
     * {@code ddl-auto=validate} and to the upsert.
     */
    @Formula("case when last_updated is null then 1 else 0 end")
    private Integer lastUpdatedMissing;

    /** Required by JPA to instantiate the class when reading rows. */
    protected GithubRepository() {
    }

    public GithubRepository(Long id, String name, String description, String owner, String language,
                            Integer stars, Integer forks, Instant lastUpdated, String htmlUrl,
                            Integer openIssues, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.language = language;
        this.stars = stars;
        this.forks = forks;
        this.lastUpdated = lastUpdated;
        this.htmlUrl = htmlUrl;
        this.openIssues = openIssues;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getOwner() {
        return owner;
    }

    public String getLanguage() {
        return language;
    }

    public Integer getStars() {
        return stars;
    }

    public Integer getForks() {
        return forks;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public Integer getOpenIssues() {
        return openIssues;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GithubRepository that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
