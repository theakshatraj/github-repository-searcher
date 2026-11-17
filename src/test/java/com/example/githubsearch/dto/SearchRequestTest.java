package com.example.githubsearch.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the POST search body's validation contract.
 *
 * <p>These tests exercise the real Bean Validation implementation (Hibernate Validator), not a Spring
 * context. That keeps them milliseconds fast while still proving the exact annotations a controller's
 * {@code @Valid} would trigger in Phase 4.
 *
 * <p>Two groups of rules are covered:
 * <ul>
 *   <li>per-field constraints ({@code @NotBlank}, {@code @Size}, {@code @Min}, {@code @Max});</li>
 *   <li>the cross-field GitHub cap, {@code page * perPage <= 1000}, which no single-field annotation can
 *       express.</li>
 * </ul>
 */
class SearchRequestTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static Set<ConstraintViolation<SearchRequest>> violationsOf(SearchRequest request) {
        return validator.validate(request);
    }

    private static void assertViolation(Set<ConstraintViolation<SearchRequest>> violations,
                                        String property, String messagePart) {
        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo(property);
                    assertThat(violation.getMessage()).contains(messagePart);
                });
    }

    // ---------------------------------------------------------------------
    // Defaults
    // ---------------------------------------------------------------------

    @Test
    void defaultsPageAndPerPageWhenTheyAreAbsent() {
        SearchRequest request = new SearchRequest("spring boot", "Java", "stars", null, null);

        assertThat(request.page()).isEqualTo(1);
        assertThat(request.perPage()).isEqualTo(30);
        assertThat(violationsOf(request)).isEmpty();
    }

    @Test
    void keepsExplicitPageAndPerPage() {
        SearchRequest request = new SearchRequest("spring boot", null, null, 4, 25);

        assertThat(request.page()).isEqualTo(4);
        assertThat(request.perPage()).isEqualTo(25);
    }

    // ---------------------------------------------------------------------
    // query
    // ---------------------------------------------------------------------

    @Test
    void rejectsBlankQuery() {
        assertViolation(violationsOf(new SearchRequest("   ", null, null, 1, 30)),
                "query", "must not be blank");
    }

    @Test
    void rejectsMissingQuery() {
        assertViolation(violationsOf(new SearchRequest(null, null, null, 1, 30)),
                "query", "must not be blank");
    }

    @Test
    void rejectsQueryLongerThan256Characters() {
        String tooLong = "q".repeat(257);

        assertViolation(violationsOf(new SearchRequest(tooLong, null, null, 1, 30)),
                "query", "256");
    }

    @Test
    void acceptsQueryAtThe256CharacterLimit() {
        String atLimit = "q".repeat(256);

        assertThat(violationsOf(new SearchRequest(atLimit, null, null, 1, 30))).isEmpty();
    }

    // ---------------------------------------------------------------------
    // language
    // ---------------------------------------------------------------------

    @Test
    void acceptsLanguagesWithPlusSharpDotsDashesAndSpaces() {
        assertThat(violationsOf(new SearchRequest("q", "C++", null, 1, 30))).isEmpty();
        assertThat(violationsOf(new SearchRequest("q", "C#", null, 1, 30))).isEmpty();
        assertThat(violationsOf(new SearchRequest("q", "Objective-C", null, 1, 30))).isEmpty();
        assertThat(violationsOf(new SearchRequest("q", "Jupyter Notebook", null, 1, 30))).isEmpty();
    }

    @Test
    void acceptsAnAbsentLanguage() {
        assertThat(violationsOf(new SearchRequest("q", null, null, 1, 30))).isEmpty();
    }

    @Test
    void rejectsLanguageWithUnsupportedCharacters() {
        assertViolation(violationsOf(new SearchRequest("q", "Java!", null, 1, 30)),
                "language", "Language");
    }

    @Test
    void rejectsLanguageLongerThan50Characters() {
        assertViolation(violationsOf(new SearchRequest("q", "A".repeat(51), null, 1, 30)),
                "language", "50");
    }

    // ---------------------------------------------------------------------
    // page / perPage
    // ---------------------------------------------------------------------

    @Test
    void rejectsPageBelowOne() {
        assertViolation(violationsOf(new SearchRequest("q", null, null, 0, 30)),
                "page", "greater than or equal to 1");
    }

    @Test
    void rejectsPerPageBelowOne() {
        assertViolation(violationsOf(new SearchRequest("q", null, null, 1, 0)),
                "perPage", "greater than or equal to 1");
    }

    @Test
    void rejectsPerPageAboveOneHundred() {
        assertViolation(violationsOf(new SearchRequest("q", null, null, 1, 101)),
                "perPage", "less than or equal to 100");
    }

    // ---------------------------------------------------------------------
    // GitHub's 1000-result cap
    // ---------------------------------------------------------------------

    @Test
    void rejectsPageTimesPerPageAboveTheGithubCap() {
        Set<ConstraintViolation<SearchRequest>> violations =
                violationsOf(new SearchRequest("q", null, null, 11, 100));

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).contains("1000"));
    }

    @Test
    void acceptsExactlyTheOneThousandResultBoundary() {
        assertThat(violationsOf(new SearchRequest("q", null, null, 10, 100))).isEmpty();
    }

    @Test
    void rejectsAHugePageWithoutOverflowingTheProduct() {
        Set<ConstraintViolation<SearchRequest>> violations =
                violationsOf(new SearchRequest("q", null, null, Integer.MAX_VALUE, 100));

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).contains("1000"));
    }
}
