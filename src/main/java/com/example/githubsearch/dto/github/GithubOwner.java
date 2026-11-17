package com.example.githubsearch.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The only part of GitHub's owner object this application stores: the login name.
 *
 * <p>GitHub sends dozens of owner fields (id, avatar_url, type, site_admin...). Modelling just
 * {@code login} plus {@code ignoreUnknown = true} keeps the DTO honest about what we use and immune to the
 * rest. The owner object itself may be {@code null} in a GitHub response, so
 * {@link GithubRepositoryItem#owner()} is nullable; the mapper skips such items.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubOwner(@JsonProperty("login") String login) {
}
