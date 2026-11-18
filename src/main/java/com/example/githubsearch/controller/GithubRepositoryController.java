package com.example.githubsearch.controller;

import com.example.githubsearch.dto.ErrorResponse;
import com.example.githubsearch.dto.RepositoryPageResponse;
import com.example.githubsearch.dto.RepositoryResponse;
import com.example.githubsearch.dto.SearchRequest;
import com.example.githubsearch.dto.SearchResponse;
import com.example.githubsearch.model.SortField;
import com.example.githubsearch.service.GithubSearchService;
import com.example.githubsearch.service.StoredRepositoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface of the API.
 *
 * <p><b>Why this class is thin.</b> A controller's job is to translate HTTP into a method call and the
 * result back into HTTP. It does not decide what a search means or how a page is sorted - those rules live
 * in {@link GithubSearchService} and {@link StoredRepositoryService}, where they can be tested without a
 * servlet container and reused from a scheduler or a message listener later. Keeping the controller free of
 * business logic means a change to the sorting rules touches one service, not two layers, and this class
 * stays short enough to read in one screen.
 *
 * <p>The little code that is here is still presentation-layer work:
 * <ul>
 *   <li>declare which inputs are required, their defaults and their limits, with Bean Validation
 *       annotations that Spring checks before the method runs;</li>
 *   <li>translate the two whitelisted strings ({@code sort}, {@code order}) into the types the service
 *       accepts, so raw client text never travels deeper into the application;</li>
 *   <li>call exactly one service method and return its result.</li>
 * </ul>
 *
 * <p><b>Why constructor injection.</b> The two collaborators are {@code final} and supplied by Spring. There
 * is no field injection and no {@code new}, so the class cannot be constructed in an invalid half-wired
 * state, and a test can instantiate it with mocks without starting a context.
 *
 * <p><b>Why no try/catch here.</b> Failures are turned into the standard error body by
 * {@link GlobalExceptionHandler}, one class for the whole API. Catching in each endpoint would duplicate
 * that mapping and risk inconsistent status codes.
 */
@RestController
@RequestMapping("/api/github")
@Tag(name = "GitHub repositories",
        description = "Search GitHub repositories, save them, and query the stored data without calling GitHub again.")
public class GithubRepositoryController {

    private static final String DEFAULT_SORT = "stars";
    private static final String DEFAULT_ORDER = "desc";
    private static final String DEFAULT_PAGE = "0";
    private static final String DEFAULT_PAGE_SIZE = "100";
    private static final int MIN_PAGE = 0;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final GithubSearchService searchService;
    private final StoredRepositoryService storedRepositoryService;

    public GithubRepositoryController(GithubSearchService searchService,
                                      StoredRepositoryService storedRepositoryService) {
        this.searchService = searchService;
        this.storedRepositoryService = storedRepositoryService;
    }

    /**
     * Fetches repositories from GitHub, stores them and returns what was saved.
     *
     * <p>{@code @Valid} is what activates the rules declared on {@link SearchRequest}. Without it the body
     * would still be parsed but a blank query would reach the service. The request is rejected before the
     * service (and therefore GitHub) is called, which is why a typo costs no upstream quota.
     *
     * @param request validated search criteria
     * @return 200 with the saved repositories, or the same status with an empty list when nothing matched
     */
    @Operation(
            summary = "Search GitHub and save the results",
            description = """
                    Calls GitHub's repository search API once, upserts every result by GitHub id, and returns \
                    the saved repositories in GitHub's order. Each call spends GitHub search quota. A search \
                    with no matches is still 200 and writes nothing.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Search completed, including zero matches.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = SearchResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Invalid request body, invalid sort, or GitHub rejected the query.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429",
                    description = "GitHub rate limit reached.",
                    headers = @Header(name = "Retry-After",
                            description = "Seconds to wait before retrying, when GitHub provided a delay.",
                            schema = @Schema(type = "integer", format = "int64")),
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502",
                    description = "GitHub is unavailable or returned an unreadable response.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503",
                    description = "PostgreSQL is temporarily unavailable.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "504",
                    description = "GitHub did not respond before the configured timeout.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500",
                    description = "Unexpected server error. Details are logged, not returned.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        return searchService.search(request);
    }

    /**
     * Reads stored repositories from PostgreSQL. This endpoint never contacts GitHub.
     *
     * @param language optional case-insensitive exact language filter, at most 50 characters
     * @param minStars optional inclusive minimum number of stars, zero or more
     * @param sort     whitelisted sort field, default {@code stars}
     * @param order    {@code asc} or {@code desc}, default {@code desc}
     * @param page     zero-based page number, default 0
     * @param size     page size between 1 and 100, default 100
     * @return 200 with one page of stored repositories; an empty page is still 200
     * @throws com.example.githubsearch.exception.InvalidRequestException when {@code sort} is not whitelisted
     */
    @Operation(
            summary = "List stored repositories",
            description = """
                    Reads only from PostgreSQL and never calls GitHub. Filters combine with AND. A valid \
                    query with no matching rows returns 200 and an empty array.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "One page of stored repositories. An empty page is still 200.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = RepositoryPageResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Invalid filter, sort, order, page or size parameter.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503",
                    description = "PostgreSQL is temporarily unavailable.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500",
                    description = "Unexpected server error. Details are logged, not returned.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/repositories")
    public RepositoryPageResponse findRepositories(
            @Parameter(description = "Case-insensitive exact language filter.",
                    example = "Java",
                    schema = @Schema(maxLength = 50))
            @RequestParam(required = false)
            @Size(max = 50, message = "language must be at most 50 characters")
            String language,

            @Parameter(description = "Inclusive minimum number of stars.",
                    example = "100",
                    schema = @Schema(type = "integer", minimum = "0"))
            @RequestParam(required = false)
            @Min(value = 0, message = "minStars must be greater than or equal to 0")
            Integer minStars,

            @Parameter(description = "Sort field. Unknown values are rejected with 400.",
                    example = "stars",
                    schema = @Schema(allowableValues = {"stars", "forks", "updated"},
                            defaultValue = "stars"))
            @RequestParam(defaultValue = DEFAULT_SORT)
            String sort,

            @Parameter(description = "Sort direction.",
                    example = "desc",
                    schema = @Schema(allowableValues = {"asc", "desc"}, defaultValue = "desc"))
            @RequestParam(defaultValue = DEFAULT_ORDER)
            @Pattern(regexp = "(?i)asc|desc", message = "order must be 'asc' or 'desc'")
            String order,

            @Parameter(description = "Zero-based page number.",
                    example = "0",
                    schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            @RequestParam(defaultValue = DEFAULT_PAGE)
            @Min(value = MIN_PAGE, message = "page must be greater than or equal to " + MIN_PAGE)
            Integer page,

            @Parameter(description = "Page size.",
                    example = "100",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "100"))
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE)
            @Min(value = MIN_PAGE_SIZE, message = "size must be greater than or equal to " + MIN_PAGE_SIZE)
            @Max(value = MAX_PAGE_SIZE, message = "size must be less than or equal to " + MAX_PAGE_SIZE)
            Integer size) {

        return storedRepositoryService.find(
                language,
                minStars,
                SortField.from(sort),
                Sort.Direction.fromString(order),
                page,
                size);
    }

    /**
     * Returns one stored repository by its GitHub id.
     *
     * @param id GitHub's numeric repository id
     * @return 200 with the repository
     * @throws com.example.githubsearch.exception.RepositoryNotFoundException when the id is not stored
     */
    @Operation(
            summary = "Get one stored repository",
            description = "Returns one repository by GitHub's numeric id. Reads only from PostgreSQL.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The stored repository.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = RepositoryResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "The id is not a number.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404",
                    description = "No repository with that GitHub id is stored.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503",
                    description = "PostgreSQL is temporarily unavailable.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500",
                    description = "Unexpected server error. Details are logged, not returned.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/repositories/{id}")
    public RepositoryResponse findById(
            @Parameter(description = "GitHub's numeric repository id.", example = "6296790", required = true)
            @PathVariable Long id) {
        return storedRepositoryService.findById(id);
    }
}
