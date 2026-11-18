package com.example.githubsearch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The request body of {@code POST /api/github/search}.
 *
 * <p>A record is the right shape for a request: it is immutable after construction, has no setters and no
 * half-built state, and Jackson can build it directly from JSON. It is a DTO, not an entity - the database
 * row is a separate class, so the API contract and the table schema can evolve independently.
 *
 * <p><b>Defaults.</b> {@code page} and {@code perPage} are optional. The compact constructor turns an
 * omitted value into 1 and 30 <em>before</em> validation runs, so every later layer (the GitHub client, the
 * service) can treat them as plain numbers and never repeat {@code value == null ? default : value}.
 *
 * <p><b>Why wrapper types.</b> {@code Integer} rather than {@code int} is what makes "omitted" detectable.
 * With a primitive, Jackson would invent 0 for a missing field and the constructor could not tell an
 * absent page from an explicit {@code "page": 0} - which is invalid, not a request for the default.
 *
 * <p><b>Why the GitHub cap lives here.</b> GitHub only serves the first 1000 search results. Asking for
 * page 11 with 100 items per page cannot succeed, so it is rejected as a client error rather than sent
 * upstream to fail. It needs a cross-field check, which {@link AssertTrue} expresses cleanly:
 * {@code page * perPage} is computed as a {@code long} so a huge page number cannot overflow into a
 * negative product and sneak past the check.
 *
 * <p>{@code sort} is intentionally unvalidated here: the whitelist lives in {@code SortField.from}, which
 * is the single place that converts client text into a query-safe value and produces the "allowed values"
 * message.
 *
 * @param query   words matched against repository names; required, at most 256 characters
 * @param language optional GitHub language filter; {@code C++}, {@code C#} and spaces are allowed
 * @param sort    optional GitHub sort: {@code stars}, {@code forks} or {@code updated}; {@code null} keeps
 *                GitHub's best-match order
 * @param page    one-based page number, default 1
 * @param perPage page size, default 30, between 1 and 100
 */
public record SearchRequest(
        @Schema(description = "Words matched against repository names.",
                example = "spring boot", maxLength = 256)
        @NotBlank(message = "query must not be blank")
        @Size(max = 256, message = "query must be at most 256 characters")
        String query,

        @Schema(description = "Optional GitHub language filter; C++, C# and spaces are allowed.",
                example = "Java", maxLength = 50)
        @Size(max = 50, message = "language must be at most 50 characters")
        @Pattern(regexp = "^[A-Za-z0-9 +#.\\-]+$",
                message = "Language may only contain letters, digits, spaces and the characters + # . -")
        String language,

        @Schema(description = "Optional GitHub sort field; omit it to keep GitHub's best-match order.",
                example = "stars", allowableValues = {"stars", "forks", "updated"})
        String sort,

        @Schema(description = "One-based page number.", example = "1", minimum = "1", defaultValue = "1")
        @Min(value = 1, message = "page must be greater than or equal to 1")
        Integer page,

        @Schema(description = "Page size. page * perPage must not exceed GitHub's 1000-result cap.",
                example = "30", minimum = "1", maximum = "100", defaultValue = "30")
        @Min(value = 1, message = "perPage must be greater than or equal to 1")
        @Max(value = 100, message = "perPage must be less than or equal to 100")
        Integer perPage) {

    /** One-based page number GitHub will be asked for when the caller omits {@code page}. */
    public static final int DEFAULT_PAGE = 1;

    /** Page size GitHub will be asked for when the caller omits {@code perPage}. */
    public static final int DEFAULT_PER_PAGE = 30;

    /** GitHub's hard ceiling on search results: {@code page * perPage} may not exceed this. */
    public static final int MAX_RESULTS = 1000;

    public SearchRequest {
        page = page == null ? DEFAULT_PAGE : page;
        perPage = perPage == null ? DEFAULT_PER_PAGE : perPage;
    }

    /**
     * Cross-field rule that no single annotation can express.
     *
     * <p>The cast to {@code long} happens before the multiplication on purpose: {@code Integer.MAX_VALUE
     * * 100} overflows to a negative {@code int}, which would compare as "under the cap" and be accepted.
     *
     * @return {@code true} when the requested window is inside GitHub's first 1000 results
     */
    @Schema(hidden = true)
    @AssertTrue(message = "page * perPage must not exceed 1000 because GitHub returns at most 1000 search results")
    public boolean isWithinGithubResultCap() {
        return (long) page * perPage <= MAX_RESULTS;
    }
}
