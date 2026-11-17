package com.example.githubsearch.model;

import com.example.githubsearch.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for the sort whitelist.
 *
 * <p>{@code SortField} is the only place where a client-supplied sort word is turned into a property name.
 * That is a security boundary: nothing raw ever reaches {@code Sort.by} or a query.
 */
class SortFieldTest {

    @Test
    void exposesTheGithubValueAndTheEntityProperty() {
        assertThat(SortField.STARS.githubValue()).isEqualTo("stars");
        assertThat(SortField.STARS.entityProperty()).isEqualTo("stars");

        assertThat(SortField.FORKS.githubValue()).isEqualTo("forks");
        assertThat(SortField.FORKS.entityProperty()).isEqualTo("forks");

        assertThat(SortField.UPDATED.githubValue()).isEqualTo("updated");
        assertThat(SortField.UPDATED.entityProperty()).isEqualTo("lastUpdated");
    }

    @Test
    void resolvesValuesCaseInsensitively() {
        assertThat(SortField.from("stars")).isEqualTo(SortField.STARS);
        assertThat(SortField.from("STARS")).isEqualTo(SortField.STARS);
        assertThat(SortField.from("Forks")).isEqualTo(SortField.FORKS);
        assertThat(SortField.from("Updated")).isEqualTo(SortField.UPDATED);
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertThat(SortField.from("  stars  ")).isEqualTo(SortField.STARS);
    }

    @Test
    void returnsNullForNullSoTheCallerCanApplyItsOwnDefault() {
        assertThat(SortField.from(null)).isNull();
    }

    @Test
    void rejectsUnknownValuesAndListsTheAllowedOnes() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> SortField.from("rating"))
                .withMessageContaining("rating")
                .withMessageContaining("Allowed values: stars, forks, updated");
    }

    @Test
    void rejectsBlankValuesAndListsTheAllowedOnes() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> SortField.from("   "))
                .withMessageContaining("must not be blank")
                .withMessageContaining("Allowed values: stars, forks, updated");
    }
}
