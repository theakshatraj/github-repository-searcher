package com.example.githubsearch.repository;

import com.example.githubsearch.entity.GithubRepository;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/**
 * Small, composable predicates for the optional filters on the read endpoint.
 *
 * <p>Why {@link Specification} instead of a JPQL query with nullable parameters: the classic
 * {@code where (:language is null or lower(language) = :language)} pattern makes PostgreSQL's type
 * inference struggle and forces one query to handle every combination. Here each absent filter simply
 * contributes no predicate (returns {@code null}), and Spring Data composes whatever is present with AND -
 * so the SQL only ever contains the conditions the caller actually asked for.
 *
 * <p>Every value is a bound parameter; nothing is concatenated into SQL. {@code query} and {@code cb} come
 * from the Criteria API, which is the safe, whitelisted way to build a WHERE clause.
 */
public final class GithubRepositorySpecs {

    private GithubRepositorySpecs() {
    }

    /**
     * Case-insensitive exact match on language, backed by the {@code idx_repo_language_lower} functional
     * index. Rows with a {@code null} language never match, which is the intended behaviour: filtering for
     * Java must not return repositories whose language GitHub could not detect.
     *
     * @return {@code null} (no predicate) when the argument is {@code null} or blank
     */
    public static Specification<GithubRepository> hasLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(criteriaBuilder.lower(root.get("language")), normalized);
    }

    /**
     * Inclusive lower bound on stars: {@code stars >= minStars}, so a repository with exactly
     * {@code minStars} stars is returned.
     *
     * @return {@code null} (no predicate) when the argument is {@code null}
     */
    public static Specification<GithubRepository> minStars(Integer minStars) {
        if (minStars == null) {
            return null;
        }
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.greaterThanOrEqualTo(root.get("stars"), minStars);
    }
}
