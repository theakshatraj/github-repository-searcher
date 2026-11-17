package com.example.githubsearch.mapper;

import com.example.githubsearch.dto.RepositoryResponse;
import com.example.githubsearch.dto.github.GithubRepositoryItem;
import com.example.githubsearch.dto.github.GithubSearchResponse;
import com.example.githubsearch.entity.GithubRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Converts between GitHub's JSON shape, our entity and our public response - and applies the only null
 * rules in the application.
 *
 * <p><b>Why a separate class.</b> Three shapes exist for the same repository: GitHub's wire DTO, the JPA
 * entity and the public JSON. If translation were spread across the service, the client and the controller,
 * each would grow its own idea of what a missing count means. One pure class means one answer, and it can be
 * unit-tested in milliseconds because it performs no I/O and needs no Spring context (it is a
 * {@link Component} only so the service can inject it).
 *
 * <p><b>The null rules.</b> The database has three NOT NULL columns beyond the id ({@code name},
 * {@code owner}, and the three counts, which default to 0). GitHub may omit any of them, so the mapper is
 * the boundary that prevents a malformed upstream item from turning into a constraint violation:
 * <ul>
 *   <li>no id, no name or no owner login means the row cannot be identified or read back - skip it and log
 *       a warning, rather than fail the whole batch because of one bad item;</li>
 *   <li>a missing stars/forks/open-issues count becomes 0, matching the column default;</li>
 *   <li>description, language and timestamps stay {@code null}: GitHub saying nothing is different from
 *       GitHub saying zero, and the columns allow null.</li>
 * </ul>
 *
 * <p><b>Instants.</b> {@code updated_at} and {@code created_at} arrive as {@link java.time.Instant}
 * because Jackson's Java time module parsed them from ISO-8601. Nothing here parses date strings by hand,
 * so timezone and precision bugs have one less place to hide.
 *
 * <p>GitHub's item order is preserved for valid items; the caller returns them to the client in the order
 * GitHub ranked them.
 */
@Component
public class GithubRepositoryMapper {

    private static final Logger log = LoggerFactory.getLogger(GithubRepositoryMapper.class);

    /** Mirrors the database defaults for {@code stars}, {@code forks} and {@code open_issues_count}. */
    private static final int DEFAULT_COUNT = 0;

    /**
     * Maps a GitHub search result to storable entities.
     *
     * @param response GitHub's parsed answer; may be {@code null} defensively
     * @return valid entities in GitHub's order; never {@code null}
     */
    public List<GithubRepository> toEntities(GithubSearchResponse response) {
        if (response == null || response.items() == null) {
            return List.of();
        }
        return response.items().stream()
                .map(this::toEntity)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Maps a stored entity to the public shape.
     *
     * <p>Every field is copied explicitly. There is no reflection-based bean copy on purpose: an added entity
     * field should be a deliberate API decision, not something that leaks automatically.
     */
    public RepositoryResponse toResponse(GithubRepository entity) {
        return new RepositoryResponse(
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

    /** Maps stored entities in order; used to echo back exactly what was just saved. */
    public List<RepositoryResponse> toResponses(List<GithubRepository> entities) {
        return entities.stream().map(this::toResponse).toList();
    }

    /**
     * Converts one item, or skips it when a required value is missing.
     *
     * <p>{@link Optional} here is a small, local signalling tool, not a field type: it makes "this item was
     * rejected" explicit in the stream and keeps the skipping rule in one place.
     */
    private Optional<GithubRepository> toEntity(GithubRepositoryItem item) {
        if (item == null) {
            log.warn("Skipping a null entry in GitHub's search result items");
            return Optional.empty();
        }
        String ownerLogin = item.owner() == null ? null : item.owner().login();
        if (item.id() == null || !StringUtils.hasText(item.name()) || !StringUtils.hasText(ownerLogin)) {
            // The id is logged when GitHub sent one; that is the only value that can identify the bad item.
            log.warn("Skipping GitHub repository item with id {} because a required field is missing "
                    + "(name and owner login must be present)", item.id());
            return Optional.empty();
        }
        return Optional.of(new GithubRepository(
                item.id(),
                item.name(),
                item.description(),
                ownerLogin,
                item.language(),
                defaultCount(item.stars()),
                defaultCount(item.forks()),
                item.lastUpdated(),
                item.htmlUrl(),
                defaultCount(item.openIssues()),
                item.createdAt()));
    }

    private static int defaultCount(Integer count) {
        return count == null ? DEFAULT_COUNT : count;
    }
}
