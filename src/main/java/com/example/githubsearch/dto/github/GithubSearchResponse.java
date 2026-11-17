package com.example.githubsearch.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The top level of GitHub's {@code GET /search/repositories} response.
 *
 * <p>A record is the smallest correct representation of JSON we only read: immutable, no setters, no
 * half-built object. Jackson (via Spring) constructs it from the response body.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is essential, not cosmetic: GitHub adds response
 * fields regularly, and without it any new field would fail deserialisation with
 * {@code UnrecognizedPropertyException} - an outage caused by someone else's harmless change.
 *
 * <p>Every component is a wrapper type on purpose. Missing JSON must become {@code null} so the mapper can
 * decide the default; a primitive would turn "absent" into {@code 0}/{@code false} and hide the difference.
 *
 * @param totalCount       how many repositories matched (GitHub caps usable results at 1000)
 * @param incompleteResults true when GitHub timed out mid-search and returned partial results
 * @param items            the page of repositories; {@code null} when GitHub sent no {@code items} field
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubSearchResponse(
        @JsonProperty("total_count") Integer totalCount,
        @JsonProperty("incomplete_results") Boolean incompleteResults,
        @JsonProperty("items") List<GithubRepositoryItem> items) {
}
