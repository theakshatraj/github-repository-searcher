package com.example.githubsearch.service;

import com.example.githubsearch.entity.GithubRepository;
import com.example.githubsearch.mapper.GithubRepositoryMapper;
import com.example.githubsearch.support.PostgresTestConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
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
