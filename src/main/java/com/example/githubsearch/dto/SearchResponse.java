package com.example.githubsearch.dto;

import java.util.List;

/**
 * The body of {@code POST /api/github/search}: a human-readable outcome plus the repositories that were
 * fetched and saved.
 *
 * <p>The message distinguishes the two successful outcomes without changing the status code: a search that
 * matched nothing and a search that saved results are both HTTP 200, so clients get an explanation instead
 * of guessing from an empty array. Failures never use this shape - they throw and become the standard
 * error body in Phase 4, so "no results" can never be mistaken for "we do not know".
 *
 * @param message      one of the two fixed success messages
 * @param repositories the fetched repositories in GitHub's order; empty when nothing matched
 */
public record SearchResponse(String message, List<RepositoryResponse> repositories) {
}
