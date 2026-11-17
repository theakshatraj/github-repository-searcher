package com.example.githubsearch.mapper;

import com.example.githubsearch.dto.RepositoryResponse;
import com.example.githubsearch.dto.github.GithubOwner;
import com.example.githubsearch.dto.github.GithubRepositoryItem;
import com.example.githubsearch.dto.github.GithubSearchResponse;
import com.example.githubsearch.entity.GithubRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the only class that translates GitHub's shape into ours.
 *
 * <p>No mocks and no Spring context: the mapper is pure, so these tests document the null rules that keep
 * the database's NOT NULL columns safe. The rules are:
 * <ul>
 *   <li>an item without an id, a name or an owner login cannot be stored and is skipped;</li>
 *   <li>counts that GitHub omitted become 0 (the database default);</li>
 *   <li>description, language and timestamps stay null - "GitHub did not tell us" is real information.</li>
 * </ul>
 */
class GithubRepositoryMapperTest {

    private static final Instant LAST_UPDATED = Instant.parse("2026-11-10T09:12:00Z");
    private static final Instant CREATED_AT = Instant.parse("2012-10-19T04:00:00Z");

    private final GithubRepositoryMapper mapper = new GithubRepositoryMapper();

    // ---------------------------------------------------------------------
    // GitHub item -> entity
    // ---------------------------------------------------------------------

    @Test
    void mapsEveryFieldIncludingTheThreeExtraOnes() {
        GithubRepositoryItem item = new GithubRepositoryItem(
                6296790L, "spring-boot", "Spring Boot helps you build applications",
                new GithubOwner("spring-projects"), "Java", 75000, 41000, LAST_UPDATED,
                "https://github.com/spring-projects/spring-boot", 12, CREATED_AT);

        List<GithubRepository> entities = mapper.toEntities(responseOf(item));

        assertThat(entities).singleElement().satisfies(entity -> {
            assertThat(entity.getId()).isEqualTo(6296790L);
            assertThat(entity.getName()).isEqualTo("spring-boot");
            assertThat(entity.getDescription()).isEqualTo("Spring Boot helps you build applications");
            assertThat(entity.getOwner()).isEqualTo("spring-projects");
            assertThat(entity.getLanguage()).isEqualTo("Java");
            assertThat(entity.getStars()).isEqualTo(75000);
            assertThat(entity.getForks()).isEqualTo(41000);
            assertThat(entity.getLastUpdated()).isEqualTo(LAST_UPDATED);
            assertThat(entity.getHtmlUrl()).isEqualTo("https://github.com/spring-projects/spring-boot");
            assertThat(entity.getOpenIssues()).isEqualTo(12);
            assertThat(entity.getCreatedAt()).isEqualTo(CREATED_AT);
        });
    }

    @Test
    void keepsNullDescriptionAndLanguageAsNull() {
        GithubRepositoryItem item = new GithubRepositoryItem(
                1L, "tiny-notes", null, new GithubOwner("alice"), null, 3, 0, null, null, 0, null);

        GithubRepository entity = mapper.toEntities(responseOf(item)).getFirst();

        assertThat(entity.getDescription()).isNull();
        assertThat(entity.getLanguage()).isNull();
        assertThat(entity.getLastUpdated()).isNull();
        assertThat(entity.getHtmlUrl()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
    }

    @Test
    void defaultsMissingCountsToZero() {
        GithubRepositoryItem item = new GithubRepositoryItem(
                2L, "counts-missing", null, new GithubOwner("bob"), "Go", null, null, null, null, null, null);

        GithubRepository entity = mapper.toEntities(responseOf(item)).getFirst();

        assertThat(entity.getStars()).isZero();
        assertThat(entity.getForks()).isZero();
        assertThat(entity.getOpenIssues()).isZero();
    }

    @Test
    void skipsAnItemWithoutAnId() {
        GithubRepositoryItem noId = item(null, "no-id", new GithubOwner("alice"));
        GithubRepositoryItem valid = item(10L, "valid", new GithubOwner("bob"));

        List<GithubRepository> entities = mapper.toEntities(responseOf(noId, valid));

        assertThat(entities).extracting(GithubRepository::getId).containsExactly(10L);
    }

    @Test
    void skipsAnItemWithoutAName() {
        GithubRepositoryItem noName = item(11L, null, new GithubOwner("alice"));
        GithubRepositoryItem valid = item(12L, "valid", new GithubOwner("bob"));

        List<GithubRepository> entities = mapper.toEntities(responseOf(noName, valid));

        assertThat(entities).extracting(GithubRepository::getId).containsExactly(12L);
    }

    @Test
    void skipsAnItemWithoutAnOwnerObject() {
        GithubRepositoryItem noOwner = item(13L, "orphan", null);
        GithubRepositoryItem valid = item(14L, "valid", new GithubOwner("bob"));

        List<GithubRepository> entities = mapper.toEntities(responseOf(noOwner, valid));

        assertThat(entities).extracting(GithubRepository::getId).containsExactly(14L);
    }

    @Test
    void skipsAnItemWhoseOwnerHasNoLogin() {
        GithubRepositoryItem blankOwner = item(15L, "blank-owner", new GithubOwner(null));
        GithubRepositoryItem valid = item(16L, "valid", new GithubOwner("bob"));

        List<GithubRepository> entities = mapper.toEntities(responseOf(blankOwner, valid));

        assertThat(entities).extracting(GithubRepository::getId).containsExactly(16L);
    }

    @Test
    void preservesGitHubOrderWhenSomeItemsAreSkipped() {
        GithubRepositoryItem first = item(21L, "first", new GithubOwner("a"));
        GithubRepositoryItem invalid = item(22L, null, new GithubOwner("b"));
        GithubRepositoryItem third = item(23L, "third", new GithubOwner("c"));

        List<GithubRepository> entities = mapper.toEntities(responseOf(first, invalid, third));

        assertThat(entities).extracting(GithubRepository::getName).containsExactly("first", "third");
    }

    @Test
    void returnsAnEmptyListWhenThereAreNoItems() {
        assertThat(mapper.toEntities(new GithubSearchResponse(0, false, List.of()))).isEmpty();
    }

    @Test
    void returnsAnEmptyListWhenItemsIsNull() {
        assertThat(mapper.toEntities(new GithubSearchResponse(null, false, null))).isEmpty();
    }

    @Test
    void treatsANullResponseAsNoItems() {
        assertThat(mapper.toEntities(null)).isEmpty();
    }

    @Test
    void ignoresNullEntriesInsideTheItemsArray() {
        List<GithubRepositoryItem> items = Arrays.asList(
                null, item(31L, "valid", new GithubOwner("alice")));

        List<GithubRepository> entities = mapper.toEntities(new GithubSearchResponse(1, false, items));

        assertThat(entities).extracting(GithubRepository::getId).containsExactly(31L);
    }

    // ---------------------------------------------------------------------
    // entity -> public response
    // ---------------------------------------------------------------------

    @Test
    void mapsAnEntityToThePublicResponseIncludingTheExtraFields() {
        GithubRepository entity = new GithubRepository(
                6296790L, "spring-boot", "desc", "spring-projects", "Java", 75000, 41000,
                LAST_UPDATED, "https://github.com/spring-projects/spring-boot", 12, CREATED_AT);

        RepositoryResponse response = mapper.toResponse(entity);

        assertThat(response.id()).isEqualTo(6296790L);
        assertThat(response.name()).isEqualTo("spring-boot");
        assertThat(response.description()).isEqualTo("desc");
        assertThat(response.owner()).isEqualTo("spring-projects");
        assertThat(response.language()).isEqualTo("Java");
        assertThat(response.stars()).isEqualTo(75000);
        assertThat(response.forks()).isEqualTo(41000);
        assertThat(response.lastUpdated()).isEqualTo(LAST_UPDATED);
        assertThat(response.htmlUrl()).isEqualTo("https://github.com/spring-projects/spring-boot");
        assertThat(response.openIssues()).isEqualTo(12);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void keepsNullsWhenMappingAnEntityToTheResponse() {
        GithubRepository entity = new GithubRepository(
                1L, "tiny-notes", null, "alice", null, 0, 0, null, null, 0, null);

        RepositoryResponse response = mapper.toResponse(entity);

        assertThat(response.description()).isNull();
        assertThat(response.language()).isNull();
        assertThat(response.lastUpdated()).isNull();
        assertThat(response.htmlUrl()).isNull();
        assertThat(response.createdAt()).isNull();
    }

    @Test
    void mapsAListOfEntitiesInOrder() {
        GithubRepository first = new GithubRepository(
                1L, "alpha", null, "alice", "Java", 10, 1, null, null, 0, null);
        GithubRepository second = new GithubRepository(
                2L, "beta", null, "bob", "Kotlin", 20, 2, null, null, 0, null);

        List<RepositoryResponse> responses = mapper.toResponses(List.of(first, second));

        assertThat(responses).extracting(RepositoryResponse::name).containsExactly("alpha", "beta");
    }

    private static GithubSearchResponse responseOf(GithubRepositoryItem... items) {
        return new GithubSearchResponse(items.length, false, Arrays.asList(items));
    }

    private static GithubRepositoryItem item(Long id, String name, GithubOwner owner) {
        return new GithubRepositoryItem(
                id, name, null, owner, null, null, null, null, null, null, null);
    }
}
