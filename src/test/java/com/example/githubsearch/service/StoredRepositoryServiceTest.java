package com.example.githubsearch.service;

import com.example.githubsearch.dto.RepositoryPageResponse;
import com.example.githubsearch.dto.RepositoryResponse;
import com.example.githubsearch.entity.GithubRepository;
import com.example.githubsearch.exception.RepositoryNotFoundException;
import com.example.githubsearch.mapper.GithubRepositoryMapper;
import com.example.githubsearch.model.SortField;
import com.example.githubsearch.repository.GithubRepositoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the storage service.
 *
 * <p>The repository is a Mockito mock, so these tests assert the <em>contract</em> the service sends to the
 * database layer: which {@link Specification} and {@link Pageable} it builds, which rows it maps, and that
 * the batch write calls {@code upsert} exactly once per entity. Whether PostgreSQL actually executes that
 * SQL is the integration test's job - a mock cannot prove SQL, and a database test is too slow to pin down
 * sort construction.
 *
 * <p>The mapper is the real implementation, not a mock: it has no dependencies and its null rules are
 * already covered by its own test, so using it here proves the two collaborate correctly.
 */
@ExtendWith(MockitoExtension.class)
class StoredRepositoryServiceTest {

    private static final Instant LAST_UPDATED = Instant.parse("2026-11-10T09:12:00Z");
    private static final Instant CREATED_AT = Instant.parse("2012-10-19T04:00:00Z");

    @Mock
    private GithubRepositoryRepository repository;

    private final GithubRepositoryMapper mapper = new GithubRepositoryMapper();

    private StoredRepositoryService service;

    @BeforeEach
    void setUp() {
        service = new StoredRepositoryService(repository, mapper);
    }

    // ---------------------------------------------------------------------
    // find(...)
    // ---------------------------------------------------------------------

    @Test
    void findBuildsAPageableWithTheRequestedPropertyDirectionAndIdTieBreaker() {
        GithubRepository entity = entity(1L, "alpha");
        when(repository.findAll(ArgumentMatchers.<Specification<GithubRepository>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity), PageRequest.of(1, 25), 51));

        RepositoryPageResponse response =
                service.find("Java", 100, SortField.STARS, Sort.Direction.DESC, 1, 25);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(
                ArgumentMatchers.<Specification<GithubRepository>>any(), pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(25);
        assertThat(pageable.getSort().toList())
                .containsExactly(Sort.Order.desc("stars"), Sort.Order.asc("id"));

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(25);
        assertThat(response.totalElements()).isEqualTo(51L);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.repositories()).extracting(RepositoryResponse::id).containsExactly(1L);
    }

    @Test
    void findDefaultsToStarsDescendingWhenNoSortOrDirectionIsGiven() {
        when(repository.findAll(ArgumentMatchers.<Specification<GithubRepository>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        service.find(null, null, null, null, 0, 100);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(
                ArgumentMatchers.<Specification<GithubRepository>>any(), pageableCaptor.capture());

        assertThat(pageableCaptor.getValue().getSort().toList())
                .containsExactly(Sort.Order.desc("stars"), Sort.Order.asc("id"));
    }

    @Test
    void findSortsForksByTheForksPropertyAndKeepsTheDirection() {
        when(repository.findAll(ArgumentMatchers.<Specification<GithubRepository>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        service.find(null, null, SortField.FORKS, Sort.Direction.ASC, 0, 10);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(
                ArgumentMatchers.<Specification<GithubRepository>>any(), pageableCaptor.capture());

        assertThat(pageableCaptor.getValue().getSort().toList())
                .containsExactly(Sort.Order.asc("forks"), Sort.Order.asc("id"));
    }

    @Test
    void findPutsRepositoriesWithoutAnUpdateTimestampLastWhenSortingByUpdated() {
        when(repository.findAll(ArgumentMatchers.<Specification<GithubRepository>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        service.find(null, null, SortField.UPDATED, Sort.Direction.DESC, 0, 10);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(
                ArgumentMatchers.<Specification<GithubRepository>>any(), pageableCaptor.capture());

        // The flag is 0 when last_updated exists and 1 when it is null, so ascending puts nulls last.
        assertThat(pageableCaptor.getValue().getSort().toList())
                .containsExactly(
                        Sort.Order.asc("lastUpdatedMissing"),
                        Sort.Order.desc("lastUpdated"),
                        Sort.Order.asc("id"));
    }

    @Test
    void findPassesACombinedSpecificationForTheOptionalFilters() {
        when(repository.findAll(ArgumentMatchers.<Specification<GithubRepository>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        service.find("java", 50, SortField.STARS, Sort.Direction.DESC, 0, 10);

        ArgumentCaptor<Specification<GithubRepository>> specCaptor = specCaptor();
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(specCaptor.capture(), pageableCaptor.capture());

        assertThat(specCaptor.getValue()).isNotNull();
    }

    // ---------------------------------------------------------------------
    // findById(...)
    // ---------------------------------------------------------------------

    @Test
    void findByIdReturnsTheMappedRepositoryWhenItExists() {
        GithubRepository entity = entity(42L, "answer");
        when(repository.findById(42L)).thenReturn(Optional.of(entity));

        RepositoryResponse response = service.findById(42L);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.name()).isEqualTo("answer");
        assertThat(response.owner()).isEqualTo("owner-answer");
        assertThat(response.htmlUrl()).isEqualTo("https://github.com/owner/answer");
        assertThat(response.openIssues()).isEqualTo(3);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void findByIdThrowsWhenTheRepositoryIsNotStored() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RepositoryNotFoundException.class)
                .isThrownBy(() -> service.findById(404L))
                .withMessageContaining("404")
                .satisfies(exception -> assertThat(exception.id()).isEqualTo(404L));
    }

    // ---------------------------------------------------------------------
    // upsertAll(...)
    // ---------------------------------------------------------------------

    @Test
    void upsertAllCallsTheNativeUpsertOncePerEntityInOrder() {
        GithubRepository first = entity(1L, "first");
        GithubRepository second = entity(2L, "second");

        service.upsertAll(List.of(first, second));

        InOrder inOrder = inOrder(repository);
        inOrder.verify(repository).upsert(
                1L, "first", "desc-first", "owner-first", "Java", 10, 2, LAST_UPDATED,
                "https://github.com/owner/first", 3, CREATED_AT);
        inOrder.verify(repository).upsert(
                2L, "second", "desc-second", "owner-second", "Java", 10, 2, LAST_UPDATED,
                "https://github.com/owner/second", 3, CREATED_AT);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void upsertAllWithAnEmptyBatchDoesNotTouchTheDatabase() {
        service.upsertAll(List.of());

        verifyNoInteractions(repository);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Specification<GithubRepository>> specCaptor() {
        return ArgumentCaptor.forClass(Specification.class);
    }

    private static GithubRepository entity(long id, String name) {
        return new GithubRepository(
                id, name, "desc-" + name, "owner-" + name, "Java", 10, 2, LAST_UPDATED,
                "https://github.com/owner/" + name, 3, CREATED_AT);
    }
}
