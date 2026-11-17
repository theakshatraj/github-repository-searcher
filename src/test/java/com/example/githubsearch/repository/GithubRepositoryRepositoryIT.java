package com.example.githubsearch.repository;

import com.example.githubsearch.entity.GithubRepository;
import com.example.githubsearch.support.PostgresTestConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for persistence against real PostgreSQL 16.
 *
 * <p>The test class opts out of Spring's per-test rollback ({@code NOT_SUPPORTED}) on purpose: a number of
 * the behaviours under test only exist once a transaction really commits - most importantly PostgreSQL's
 * {@code now()}, which is the <em>transaction</em> start time, so two upserts in one transaction would get
 * the same {@code synced_at}. Fixtures are therefore written and cleaned up through an explicit
 * {@link TransactionTemplate}, never through {@code save()}, so the production upsert stays the only write
 * path that is exercised.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GithubRepositoryRepositoryIT {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private GithubRepositoryRepository repository;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createQuery("delete from GithubRepository").executeUpdate());
    }

    private void persist(GithubRepository... repositories) {
        transactionTemplate.executeWithoutResult(status -> {
            for (GithubRepository repository : repositories) {
                entityManager.persist(repository);
            }
        });
    }

    private GithubRepository find(Long id) {
        return transactionTemplate.execute(status -> entityManager.find(GithubRepository.class, id));
    }

    @Test
    void persistsEveryColumnAndLetsTheDatabaseStampSyncedAt() {
        Instant lastUpdated = Instant.parse("2026-11-10T09:12:00Z");
        Instant createdAt = Instant.parse("2012-10-19T04:00:00Z");
        GithubRepository repository = new GithubRepository(
                6296790L, "spring-boot", "Spring Boot helps you build applications",
                "spring-projects", "Java", 75000, 41000, lastUpdated,
                "https://github.com/spring-projects/spring-boot", 12, createdAt);

        persist(repository);

        GithubRepository loaded = find(6296790L);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getName()).isEqualTo("spring-boot");
        assertThat(loaded.getDescription()).isEqualTo("Spring Boot helps you build applications");
        assertThat(loaded.getOwner()).isEqualTo("spring-projects");
        assertThat(loaded.getLanguage()).isEqualTo("Java");
        assertThat(loaded.getStars()).isEqualTo(75000);
        assertThat(loaded.getForks()).isEqualTo(41000);
        assertThat(loaded.getLastUpdated()).isEqualTo(lastUpdated);
        assertThat(loaded.getHtmlUrl()).isEqualTo("https://github.com/spring-projects/spring-boot");
        assertThat(loaded.getOpenIssues()).isEqualTo(12);
        assertThat(loaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(loaded.getSyncedAt()).isNotNull();
    }

    @Test
    void acceptsNullDescriptionLanguageLastUpdatedHtmlUrlAndCreatedAt() {
        GithubRepository repository = new GithubRepository(
                1234567L, "tiny-notes", null, "alice", null, 3, 0, null, null, 0, null);

        persist(repository);

        GithubRepository loaded = find(1234567L);
        assertThat(loaded.getDescription()).isNull();
        assertThat(loaded.getLanguage()).isNull();
        assertThat(loaded.getLastUpdated()).isNull();
        assertThat(loaded.getHtmlUrl()).isNull();
        assertThat(loaded.getCreatedAt()).isNull();
        assertThat(loaded.getStars()).isEqualTo(3);
        assertThat(loaded.getOpenIssues()).isZero();
    }

    @Test
    void roundTripsInstantsWithoutLosingPrecisionOrShiftingZones() {
        Instant lastUpdated = Instant.parse("2026-11-10T09:12:00.123456Z");

        persist(new GithubRepository(
                42L, "instant-check", null, "alice", "Java", 1, 1, lastUpdated, null, 0, null));

        assertThat(find(42L).getLastUpdated()).isEqualTo(lastUpdated);
    }

    // ---------------------------------------------------------------------
    // Upsert: INSERT ... ON CONFLICT (id) DO UPDATE
    // ---------------------------------------------------------------------

    private void upsert(GithubRepository source) {
        transactionTemplate.executeWithoutResult(status -> repository
                .upsert(source.getId(), source.getName(), source.getDescription(),
                        source.getOwner(), source.getLanguage(), source.getStars(),
                        source.getForks(), source.getLastUpdated(), source.getHtmlUrl(),
                        source.getOpenIssues(), source.getCreatedAt()));
    }

    private long countRows() {
        Long count = transactionTemplate.execute(status -> entityManager
                .createQuery("select count(r) from GithubRepository r", Long.class)
                .getSingleResult());
        return count == null ? 0L : count;
    }

    @Test
    void upsertInsertsANewRow() {
        upsert(new GithubRepository(
                1001L, "fresh", "inserted by upsert", "octocat", "Kotlin", 10, 2,
                Instant.parse("2026-01-02T03:04:05Z"), "https://github.com/octocat/fresh", 4,
                Instant.parse("2025-01-02T03:04:05Z")));

        GithubRepository loaded = find(1001L);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getName()).isEqualTo("fresh");
        assertThat(loaded.getDescription()).isEqualTo("inserted by upsert");
        assertThat(loaded.getOwner()).isEqualTo("octocat");
        assertThat(loaded.getLanguage()).isEqualTo("Kotlin");
        assertThat(loaded.getStars()).isEqualTo(10);
        assertThat(loaded.getForks()).isEqualTo(2);
        assertThat(loaded.getOpenIssues()).isEqualTo(4);
        assertThat(loaded.getHtmlUrl()).isEqualTo("https://github.com/octocat/fresh");
        assertThat(loaded.getCreatedAt()).isEqualTo(Instant.parse("2025-01-02T03:04:05Z"));
        assertThat(loaded.getSyncedAt()).isNotNull();
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void upsertUpdatesTheExistingRowInsteadOfCreatingADuplicate() {
        upsert(new GithubRepository(
                2002L, "before", "old description", "octocat", "Java", 10, 2,
                Instant.parse("2026-01-01T00:00:00Z"), "https://github.com/octocat/before", 1,
                Instant.parse("2025-01-01T00:00:00Z")));
        Instant firstSync = find(2002L).getSyncedAt();

        sleepBriefly();

        upsert(new GithubRepository(
                2002L, "after", "new description", "octocat", "Kotlin", 99, 77,
                Instant.parse("2026-06-01T00:00:00Z"), "https://github.com/octocat/after", 55,
                Instant.parse("2025-06-01T00:00:00Z")));

        assertThat(countRows()).isEqualTo(1);
        GithubRepository loaded = find(2002L);
        assertThat(loaded.getName()).isEqualTo("after");
        assertThat(loaded.getDescription()).isEqualTo("new description");
        assertThat(loaded.getLanguage()).isEqualTo("Kotlin");
        assertThat(loaded.getStars()).isEqualTo(99);
        assertThat(loaded.getForks()).isEqualTo(77);
        assertThat(loaded.getOpenIssues()).isEqualTo(55);
        assertThat(loaded.getHtmlUrl()).isEqualTo("https://github.com/octocat/after");
        assertThat(loaded.getLastUpdated()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(loaded.getCreatedAt()).isEqualTo(Instant.parse("2025-06-01T00:00:00Z"));
        assertThat(loaded.getSyncedAt()).isAfter(firstSync);
    }

    @Test
    void upsertToleratesTheSameIdTwiceInsideOneTransaction() {
        GithubRepository firstVersion = new GithubRepository(
                3003L, "first-write", null, "octocat", "Java", 1, 1, null, null, 0, null);
        GithubRepository secondVersion = new GithubRepository(
                3003L, "second-write", "wins", "octocat", "Kotlin", 2, 2, null, null, 0, null);

        transactionTemplate.executeWithoutResult(status -> {
            repository.upsert(firstVersion.getId(), firstVersion.getName(), firstVersion.getDescription(),
                    firstVersion.getOwner(), firstVersion.getLanguage(), firstVersion.getStars(),
                    firstVersion.getForks(), firstVersion.getLastUpdated(), firstVersion.getHtmlUrl(),
                    firstVersion.getOpenIssues(), firstVersion.getCreatedAt());
            repository.upsert(secondVersion.getId(), secondVersion.getName(), secondVersion.getDescription(),
                    secondVersion.getOwner(), secondVersion.getLanguage(), secondVersion.getStars(),
                    secondVersion.getForks(), secondVersion.getLastUpdated(), secondVersion.getHtmlUrl(),
                    secondVersion.getOpenIssues(), secondVersion.getCreatedAt());
        });

        assertThat(countRows()).isEqualTo(1);
        assertThat(find(3003L).getName()).isEqualTo("second-write");
        assertThat(find(3003L).getLanguage()).isEqualTo("Kotlin");
    }

    @Test
    void upsertAcceptsNullOptionalFields() {
        upsert(new GithubRepository(
                4004L, "nullable", null, "alice", null, 0, 0, null, null, 0, null));

        GithubRepository loaded = find(4004L);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getDescription()).isNull();
        assertThat(loaded.getLanguage()).isNull();
        assertThat(loaded.getLastUpdated()).isNull();
        assertThat(loaded.getHtmlUrl()).isNull();
        assertThat(loaded.getCreatedAt()).isNull();
        assertThat(loaded.getSyncedAt()).isNotNull();
    }

    @Test
    void rollsBackEveryRowWhenOneRowInTheBatchFails() {
        GithubRepository valid = new GithubRepository(
                5005L, "valid-row", null, "alice", "Java", 1, 1, null, null, 0, null);

        assertThatExceptionOfType(RuntimeException.class).isThrownBy(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    repository.upsert(valid.getId(), valid.getName(), valid.getDescription(),
                            valid.getOwner(), valid.getLanguage(), valid.getStars(), valid.getForks(),
                            valid.getLastUpdated(), valid.getHtmlUrl(), valid.getOpenIssues(),
                            valid.getCreatedAt());
                    // name is NOT NULL: this row must fail and take the previous row with it
                    repository.upsert(5006L, null, null, "alice", "Java", 1, 1, null, null, 0, null);
                }));

        assertThat(countRows()).isZero();
        assertThat(find(5005L)).isNull();
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting between upserts", interrupted);
        }
    }

    // ---------------------------------------------------------------------
    // Filtering (Specifications) and sorting
    // ---------------------------------------------------------------------

    private void seedFilterAndSortFixtures() {
        persist(
                new GithubRepository(1L, "alpha", "Java, 100 stars", "alice", "Java", 100, 10,
                        Instant.parse("2024-01-01T00:00:00Z"), null, 1, null),
                new GithubRepository(2L, "beta", "lowercase language, 50 stars", "bob", "java", 50, 20,
                        Instant.parse("2025-01-01T00:00:00Z"), null, 2, null),
                new GithubRepository(3L, "gamma", "Kotlin, 100 stars, never updated", "carol", "Kotlin", 100, 30,
                        null, null, 3, null),
                new GithubRepository(4L, "delta", "no language, 0 stars", "dave", null, 0, 0,
                        Instant.parse("2023-01-01T00:00:00Z"), null, 0, null),
                new GithubRepository(5L, "epsilon", "Java, 75 stars, newest", "erin", "Java", 75, 10,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 5, null));
    }

    private List<Long> idsMatching(Specification<GithubRepository> specification, Sort sort) {
        return transactionTemplate.execute(status -> repository
                .findAll(specification, PageRequest.of(0, 100, sort))
                .map(GithubRepository::getId)
                .getContent());
    }

    private List<Long> idsMatching(Specification<GithubRepository> specification, Sort sort, int page, int size) {
        return transactionTemplate.execute(status -> repository
                .findAll(specification, PageRequest.of(page, size, sort))
                .map(GithubRepository::getId)
                .getContent());
    }

    private static Sort descendingBy(String property) {
        return Sort.by(Sort.Order.desc(property)).and(Sort.by(Sort.Order.asc("id")));
    }

    @Test
    void noFiltersMatchEverything() {
        seedFilterAndSortFixtures();

        assertThat(idsMatching(null, descendingBy("stars")))
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void languageFilterIsCaseInsensitiveAndExcludesRowsWithoutLanguage() {
        seedFilterAndSortFixtures();

        assertThat(idsMatching(GithubRepositorySpecs.hasLanguage("Java"), Sort.by(Sort.Order.asc("id"))))
                .containsExactly(1L, 2L, 5L);
    }

    @Test
    void blankLanguageMeansNoFilter() {
        seedFilterAndSortFixtures();

        assertThat(idsMatching(GithubRepositorySpecs.hasLanguage("   "), descendingBy("stars")))
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void minStarsIsInclusiveAtTheBoundary() {
        seedFilterAndSortFixtures();

        assertThat(idsMatching(GithubRepositorySpecs.minStars(75), descendingBy("stars")))
                .containsExactlyInAnyOrder(1L, 3L, 5L);
        assertThat(idsMatching(GithubRepositorySpecs.minStars(0), descendingBy("stars")))
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void filtersCombineWithAnd() {
        seedFilterAndSortFixtures();

        Specification<GithubRepository> combined = GithubRepositorySpecs.hasLanguage("Java")
                .and(GithubRepositorySpecs.minStars(75));

        assertThat(idsMatching(combined, descendingBy("stars"))).containsExactly(1L, 5L);
    }

    @Test
    void sortsByStarsInBothDirectionsUsingIdAsTheTieBreaker() {
        seedFilterAndSortFixtures();

        assertThat(idsMatching(null, descendingBy("stars")))
                .containsExactly(1L, 3L, 5L, 2L, 4L);
        assertThat(idsMatching(null, Sort.by(Sort.Order.asc("stars")).and(Sort.by(Sort.Order.asc("id")))))
                .containsExactly(4L, 2L, 5L, 1L, 3L);
    }

    @Test
    void sortsByForksInBothDirections() {
        seedFilterAndSortFixtures();

        assertThat(idsMatching(null, descendingBy("forks")))
                .containsExactly(3L, 2L, 1L, 5L, 4L);
        assertThat(idsMatching(null, Sort.by(Sort.Order.asc("forks")).and(Sort.by(Sort.Order.asc("id")))))
                .containsExactly(4L, 1L, 5L, 2L, 3L);
    }

    @Test
    void sortsByLastUpdatedInBothDirectionsWithNullsLast() {
        seedFilterAndSortFixtures();

        // Plain Order.desc("lastUpdated") puts NULLs FIRST on PostgreSQL (verified: [3,5,2,1,4]),
        // and Sort.Order.nullsLast() is rejected by Spring Data's Criteria path with
        // "Applying Null Precedence using Criteria Queries is not yet supported".
        // The entity therefore exposes a computed flag: 0 when last_updated is present, 1 when it is null.
        Sort descendingNullsLast = Sort.by(
                        Sort.Order.asc("lastUpdatedMissing"),
                        Sort.Order.desc("lastUpdated"),
                        Sort.Order.asc("id"));

        assertThat(idsMatching(null, descendingNullsLast))
                .containsExactly(5L, 2L, 1L, 4L, 3L);

        Sort ascendingNullsLast = Sort.by(
                Sort.Order.asc("lastUpdatedMissing"),
                Sort.Order.asc("lastUpdated"),
                Sort.Order.asc("id"));

        assertThat(idsMatching(null, ascendingNullsLast))
                .containsExactly(4L, 1L, 2L, 5L, 3L);
    }

    @Test
    void paginationIsStableBecauseIdBreaksTies() {
        seedFilterAndSortFixtures();

        Sort sort = descendingBy("stars");

        assertThat(idsMatching(null, sort, 0, 2)).containsExactly(1L, 3L);
        assertThat(idsMatching(null, sort, 1, 2)).containsExactly(5L, 2L);
        assertThat(idsMatching(null, sort, 2, 2)).containsExactly(4L);
    }
}
