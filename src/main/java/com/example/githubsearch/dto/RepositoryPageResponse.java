package com.example.githubsearch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One page of stored repositories plus the metadata a client needs to walk the rest.
 *
 * <p>The required {@code repositories} array is preserved; {@code page}, {@code size},
 * {@code totalElements} and {@code totalPages} are additive. Without {@code totalElements} a client cannot
 * know whether an empty page means "no more data" or "no data at all", and it could not render a page
 * count.
 *
 * <p>{@code totalElements} is a {@code long} because that is what {@code Page#getTotalElements()} returns
 * and a table can legitimately exceed {@code Integer.MAX_VALUE}; narrowing it would be a silent overflow.
 *
 * @param repositories  this page's rows, already mapped to the public shape
 * @param page          zero-based page number
 * @param size          requested page size
 * @param totalElements total rows matching the filters across all pages
 * @param totalPages    total number of pages at this size
 */
public record RepositoryPageResponse(
        @Schema(description = "This page's rows, already mapped to the public shape.")
        List<RepositoryResponse> repositories,

        @Schema(description = "Zero-based page number.", example = "0")
        int page,

        @Schema(description = "Page size used for this response.", example = "100")
        int size,

        @Schema(description = "Total rows matching the filters across every page.", example = "5")
        long totalElements,

        @Schema(description = "Total number of pages at this size.", example = "1")
        int totalPages) {
}
