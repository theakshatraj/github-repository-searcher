package com.example.githubsearch.model;

import com.example.githubsearch.exception.InvalidRequestException;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Whitelist of the fields a caller may sort by.
 *
 * <p>Each constant knows two names, because the same vocabulary means different things in the two
 * operations of this API:
 * <ul>
 *   <li>{@link #githubValue()} is the value sent to GitHub's {@code sort} parameter ({@code stars},
 *       {@code forks}, {@code updated});</li>
 *   <li>{@link #entityProperty()} is the JPA property used to order rows already stored in PostgreSQL
 *       ({@code stars}, {@code forks}, {@code lastUpdated}).</li>
 * </ul>
 *
 * <p>Why an enum instead of a {@code switch} on a {@code String}: the whitelist, the mapping and the error
 * message live in one place. A new sortable field is one constant, with no change to the service or the
 * repository - the "open for extension" part of SOLID. It also means raw client text can never reach
 * {@code Sort.by(...)} or a query, which closes the injection hole a free-form sort parameter would open.
 *
 * <p>Null handling: {@link #from(String)} returns {@code null} for a {@code null} argument. That is a
 * deliberate contract so the caller can apply its own default (GET defaults to stars; POST omits the
 * GitHub sort entirely so GitHub uses best-match order). Blank or unknown text is a client error and
 * throws.
 */
public enum SortField {

    STARS("stars", "stars"),
    FORKS("forks", "forks"),
    UPDATED("updated", "lastUpdated");

    private static final String ALLOWED_VALUES = Arrays.stream(values())
            .map(SortField::githubValue)
            .collect(Collectors.joining(", "));

    private final String githubValue;
    private final String entityProperty;

    SortField(String githubValue, String entityProperty) {
        this.githubValue = githubValue;
        this.entityProperty = entityProperty;
    }

    public String githubValue() {
        return githubValue;
    }

    public String entityProperty() {
        return entityProperty;
    }

    /**
     * Resolves client text to a whitelisted sort field.
     *
     * @param text any casing, optionally padded with whitespace; {@code null} is allowed
     * @return the matching constant, or {@code null} when {@code text} is {@code null}
     * @throws InvalidRequestException when the text is blank or not one of the allowed values
     */
    public static SortField from(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            throw new InvalidRequestException("Sort must not be blank. Allowed values: " + ALLOWED_VALUES);
        }
        return Arrays.stream(values())
                .filter(field -> field.githubValue.equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException(
                        "Invalid sort field '" + text + "'. Allowed values: " + ALLOWED_VALUES));
    }
}
