package com.example.githubsearch.service;

import com.example.githubsearch.dto.RepositoryPageResponse;
import com.example.githubsearch.dto.RepositoryResponse;
import com.example.githubsearch.entity.GithubRepository;
import com.example.githubsearch.mapper.GithubRepositoryMapper;
import com.example.githubsearch.model.SortField;
import com.example.githubsearch.support.PostgresTestConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the batch write against real PostgreSQL 16.
 *
 * <p>Why this cannot be a unit test with Mockito: the two behaviours under test are database behaviours.
 * "No duplicates" is the {@code ON CONFLICT (id) DO UPDATE} clause executing, and "all or nothing" is the
 * transaction manager rolling back statements that already ran. A mock would only prove that a method was
 * called, not that PostgreSQL did the right thing.
 *
 * <p>The class opts out of Spring's per-test rollback ({@code NOT_SUPPORTED}) deliberately: if the test
 * itself ran inside a transaction, the service's {@code @Transactional} would join it, and a rollback test
 * could never prove who rolled back what. Here each {@code upsertAll} call owns its transaction, exactly as
 * in production. Cleanup is explicit before each test.
 *
 * <p>The service and mapper are imported because {@code @DataJpaTest} only scans JPA components; importing
 * the real service is what makes this a test of the transaction boundary rather than of the repository
 * alone.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfig.class, StoredRepositoryService.class, GithubRepositoryMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StoredRepositoryServiceIT {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private StoredRepositoryService service;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createQuery("delete from GithubRepository").executeUpdate());
    }

    @Test
    void upsertingTheSameIdsTwiceUpdatesInPlaceWithoutCreatingDuplicates() {
        service.upsertAll(List.of(
                entity(1001L, "first-version", "old description", 10),
                entity(1002L, "second-version", "second description", 20)));

        service.upsertAll(List.of(
                entity(1001L, "updated-version", "new description", 99),
                entity(1002L, "second-version", "second description updated", 21)));

        assertThat(countRows()).isEqualTo(2);

        GithubRepository updated = find(1001L);
        assertThat(updated).isNotNull();
        assertThat(updated.getName()).isEqualTo("updated-version");
        assertThat(updated.getDescription()).isEqualTo("new description");
        assertThat(updated.getStars()).isEqualTo(99);

        GithubRepository second = find(1002L);
        assertThat(second.getName()).isEqualTo("second-version");
        assertThat(second.getDescription()).isEqualTo("second description updated");
        assertThat(second.getStars()).isEqualTo(21);
    }

    @Test
    void rollsBackTheWholeBatchWhenOneRowFails() {
        GithubRepository valid = entity(2001L, "valid-row", "must not survive", 1);
        // name is NOT NULL: this row violates the constraint after the valid row already ran.
        GithubRepository invalid = new GithubRepository(
                2002L, null, null, "alice", "Java", 1, 0, null, null, 0, null);

        assertThatThrownBy(() -> service.upsertAll(List.of(valid, invalid)))
                .isInstanceOf(DataAccessException.class);

        assertThat(countRows()).isZero();
        assertThat(find(2001L)).isNull();
    }

    // ---------------------------------------------------------------------
    // find(...): the production sort and filter path against PostgreSQL
    // ---------------------------------------------------------------------

    @Test
    void findSortsByUpdatedInBothDirectionsWithNullsLastAndIdAsTheTieBreaker() {
        persist(
                new GithubRepository(10L, "newest", null, "alice", "Java", 1, 1,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 0, null),
                new GithubRepository(11L, "oldest-timestamp", null, "bob", "Java", 1, 1,
                        Instant.parse("2025-01-01T00:00:00Z"), null, 0, null),
                new GithubRepository(12L, "middle", null, "carol", "Java", 1, 1,
                        Instant.parse("2025-06-01T00:00:00Z"), null, 0, null),
                new GithubRepository(13L, "never-updated", null, "dave", "Java", 1, 1,
                        null, null, 0, null),
                // Same timestamp as id 10: the ascending id tie-breaker must decide the order.
                new GithubRepository(14L, "newest-tie", null, "erin", "Java", 1, 1,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 0, null));

        assertThat(idsFrom(service.find(null, null, SortField.UPDATED, Sort.Direction.DESC, 0, 100)))
                .containsExactly(10L, 14L, 12L, 11L, 13L);
        assertThat(idsFrom(service.find(null, null, SortField.UPDATED, Sort.Direction.ASC, 0, 100)))
                .containsExactly(11L, 12L, 10L, 14L, 13L);
    }

    @Test
    void findCombinesTheLanguageAndMinStarsFiltersCaseInsensitively() {
        persist(
                new GithubRepository(20L, "java-at-the-boundary", null, "alice", "Java", 50, 1,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 0, null),
                new GithubRepository(21L, "lowercase-java-above", null, "bob", "java", 100, 1,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 0, null),
                new GithubRepository(22L, "kotlin-at-the-boundary", null, "carol", "Kotlin", 50, 1,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 0, null),
                new GithubRepository(23L, "java-below", null, "dave", "Java", 49, 1,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 0, null),
                new GithubRepository(24L, "no-language", null, "erin", null, 100, 1,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 0, null));

        RepositoryPageResponse page =
                service.find("JAVA", 50, SortField.STARS, Sort.Direction.DESC, 0, 100);

        assertThat(idsFrom(page)).containsExactly(21L, 20L);
        assertThat(page.totalElements()).isEqualTo(2);
    }

    @Test
    void findDefaultsToStarsDescendingWhenNoSortOrDirectionIsGiven() {
        persist(
                new GithubRepository(30L, "two-stars", null, "alice", "Java", 2, 1,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 0, null),
                new GithubRepository(31L, "three-stars", null, "bob", "Java", 3, 1,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 0, null),
                new GithubRepository(32L, "one-star", null, "carol", "Java", 1, 1,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 0, null));

        assertThat(idsFrom(service.find(null, null, null, null, 0, 2)))
                .containsExactly(31L, 30L);
    }

    private void persist(GithubRepository... repositories) {
        transactionTemplate.executeWithoutResult(status -> {
            for (GithubRepository repository : repositories) {
                entityManager.persist(repository);
            }
        });
    }

    private static List<Long> idsFrom(RepositoryPageResponse page) {
        return page.repositories().stream()
                .map(RepositoryResponse::id)
                .toList();
    }

    private static GithubRepository entity(long id, String name, String description, int stars) {
        return new GithubRepository(
                id, name, description, "owner", "Java", stars, 1,
                Instant.parse("2026-01-01T00:00:00Z"),
                "https://github.com/owner/" + id,
                0,
                Instant.parse("2025-01-01T00:00:00Z"));
    }

    private long countRows() {
        Long count = transactionTemplate.execute(status -> entityManager
                .createQuery("select count(r) from GithubRepository r", Long.class)
                .getSingleResult());
        return count == null ? 0L : count;
    }

    private GithubRepository find(Long id) {
        return transactionTemplate.execute(status -> entityManager.find(GithubRepository.class, id));
    }
}
