package com.example.githubsearch.service;

import com.example.githubsearch.dto.RepositoryPageResponse;
import com.example.githubsearch.dto.RepositoryResponse;
import com.example.githubsearch.entity.GithubRepository;
import com.example.githubsearch.exception.RepositoryNotFoundException;
import com.example.githubsearch.mapper.GithubRepositoryMapper;
import com.example.githubsearch.model.SortField;
import com.example.githubsearch.repository.GithubRepositoryRepository;
import com.example.githubsearch.repository.GithubRepositorySpecs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The only service that talks to the database.
 *
 * <p><b>Why it exists separately from {@code GithubSearchService}.</b> A search makes a slow, unreliable
 * HTTP call and then writes. If one method did both, the database transaction would be open for the whole
 * HTTP call: a connection is borrowed from the pool and held idle while a remote server thinks, and a slow
 * GitHub response can exhaust the pool under load. Here the HTTP call happens first, outside any
 * transaction, and only the mapped rows are handed to this class, which opens a short transaction around
 * the writes. It also means this class has no dependency on {@code GithubSearchClient}, so the read path
 * physically cannot call GitHub - the requirement is enforced by the dependency graph, not by discipline.
 *
 * <p><b>Writes.</b> {@link #upsertAll(List)} is the single write path. It loops over at most 100 rows and
 * calls the repository's native {@code INSERT ... ON CONFLICT (id) DO UPDATE}, one statement per row, inside
 * one transaction. The loop is deliberate: a single multi-row statement would be faster but much harder to
 * read, and 100 round trips is not the bottleneck. Because the loop shares one transaction, any failing row
 * rolls the whole batch back - the client never sees half a page saved.
 *
 * <p><b>Reads.</b> {@link #find} builds the optional filters as a {@link Specification} and the ordering as a
 * whitelisted {@link Sort}; PostgreSQL does the filtering and paging, never Java. The sort always appends
 * {@code id} as a tie-breaker so two rows with equal stars cannot swap places between pages.
 */
@Service
public class StoredRepositoryService {

    private static final SortField DEFAULT_SORT = SortField.STARS;
    private static final Sort.Direction DEFAULT_DIRECTION = Sort.Direction.DESC;
    private static final String ID_PROPERTY = "id";

    /**
     * Read-only entity flag: 0 when {@code last_updated} exists, 1 when it is null. See
     * {@code GithubRepository.lastUpdatedMissing} for why Spring Data's {@code nullsLast()} cannot be used
     * on the Specification path.
     */
    private static final String LAST_UPDATED_MISSING_PROPERTY = "lastUpdatedMissing";

    private final GithubRepositoryRepository repository;
    private final GithubRepositoryMapper mapper;

    public StoredRepositoryService(GithubRepositoryRepository repository, GithubRepositoryMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * Inserts or updates every entity in one transaction.
     *
     * <p>Why the service and not the repository owns the transaction: the repository method is transactional
     * on its own (so it also works standalone), but only the service knows that all rows of one GitHub page
     * form a unit. Joining them here means a failure on row 57 undoes rows 1 to 56.
     *
     * <p>An empty batch is a no-op: it avoids opening a transaction that would do nothing.
     *
     * @param entities rows built by the mapper; may be empty but not {@code null}
     */
    @Transactional
    public void upsertAll(List<GithubRepository> entities) {
        if (entities.isEmpty()) {
            return;
        }
        for (GithubRepository entity : entities) {
            repository.upsert(
                    entity.getId(),
                    entity.getName(),
                    entity.getDescription(),
                    entity.getOwner(),
                    entity.getLanguage(),
                    entity.getStars(),
                    entity.getForks(),
                    entity.getLastUpdated(),
                    entity.getHtmlUrl(),
                    entity.getOpenIssues(),
                    entity.getCreatedAt());
        }
    }

    /**
     * Reads one page of stored repositories, filtered and sorted in PostgreSQL.
     *
     * @param language  case-insensitive exact language filter; {@code null}/blank means no filter
     * @param minStars  inclusive lower bound; {@code null} means no filter
     * @param sort      whitelisted sort field; {@code null} defaults to stars
     * @param direction sort direction; {@code null} defaults to descending
     * @param page      zero-based page number (the GET endpoint uses zero-based paging)
     * @param size      page size, already validated by the controller
     * @return one page plus the metadata the client needs to page through the rest
     */
    @Transactional(readOnly = true)
    public RepositoryPageResponse find(String language, Integer minStars, SortField sort,
                                       Sort.Direction direction, int page, int size) {
        // allOf(...) ignores null specifications, so an absent filter contributes no predicate at all.
        // It is the non-deprecated replacement for Specification.where(...).and(...) in Spring Data JPA 3.5.
        Specification<GithubRepository> filters = Specification.allOf(
                GithubRepositorySpecs.hasLanguage(language),
                GithubRepositorySpecs.minStars(minStars));

        Page<GithubRepository> result =
                repository.findAll(filters, PageRequest.of(page, size, buildSort(sort, direction)));

        return new RepositoryPageResponse(
                result.map(mapper::toResponse).getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /**
     * Reads one stored repository by its GitHub id.
     *
     * @throws RepositoryNotFoundException when no row has that id; Phase 4 turns it into a 404
     */
    @Transactional(readOnly = true)
    public RepositoryResponse findById(Long id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> new RepositoryNotFoundException(id));
    }

    /**
     * Builds the ordering from the enum whitelist. The client's raw text never reaches this method.
     *
     * <p>{@code updated} needs an extra key: PostgreSQL puts nulls first for a descending sort, but rows
     * without an update timestamp must sort last, so the computed {@code lastUpdatedMissing} flag goes first
     * (ascending: 0 before 1). See the entity for why this is a formula rather than {@code nullsLast()}.
     */
    private static Sort buildSort(SortField sort, Sort.Direction direction) {
        SortField field = sort == null ? DEFAULT_SORT : sort;
        Sort.Direction effectiveDirection = direction == null ? DEFAULT_DIRECTION : direction;

        if (field == SortField.UPDATED) {
            return Sort.by(
                    Sort.Order.asc(LAST_UPDATED_MISSING_PROPERTY),
                    new Sort.Order(effectiveDirection, field.entityProperty()),
                    Sort.Order.asc(ID_PROPERTY));
        }
        return Sort.by(
                new Sort.Order(effectiveDirection, field.entityProperty()),
                Sort.Order.asc(ID_PROPERTY));
    }
}
