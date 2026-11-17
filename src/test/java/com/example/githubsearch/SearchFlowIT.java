package com.example.githubsearch;

import com.example.githubsearch.client.GithubSearchClient;
import com.example.githubsearch.dto.github.GithubOwner;
import com.example.githubsearch.dto.github.GithubRepositoryItem;
import com.example.githubsearch.dto.github.GithubSearchResponse;
import com.example.githubsearch.entity.GithubRepository;
import com.example.githubsearch.repository.GithubRepositoryRepository;
import com.example.githubsearch.support.PostgresTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests through the real web layer, the real services and a real PostgreSQL 16.
 *
 * <p><b>What is real and what is faked.</b> Only {@link GithubSearchClient} is a mock. Tomcat is replaced by
 * MockMvc, but the request still passes through Spring MVC's handler mapping, argument binding, validation,
 * the controllers, both services, Hibernate and Flyway. The database is a throwaway Testcontainers instance
 * with the production migration, so the native upsert, the filters and the sorting run as real SQL. Faking
 * GitHub is deliberate: an automated test must never spend quota or depend on the internet, and a canned
 * response makes the expected order deterministic.
 *
 * <p><b>What this proves that the unit tests cannot.</b> That the pieces fit together: the POST response
 * really is persisted, a second POST really updates rows instead of duplicating them, and a GET really reads
 * those rows back with filters and sorting while never touching the client. Those are the behaviours the
 * assignment describes, and they only exist at the integration level.
 */
@SpringBootTest(classes = GithubRepositorySearcherApplication.class)
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class SearchFlowIT {

    private static final String SAVED_MESSAGE = "Repositories fetched and saved successfully";
    private static final String EMPTY_MESSAGE = "No repositories found for the given search criteria";
    private static final Instant UPDATED_AT = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2025-01-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GithubRepositoryRepository repository;

    @MockitoBean
    private GithubSearchClient client;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void searchingSavesRepositoriesAndRepeatingItUpdatesInsteadOfDuplicating() throws Exception {
        when(client.search(anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(cannedResponse(100, 500, 900))
                .thenReturn(cannedResponse(111, 555, 999));

        postSearch("{\"query\": \"spring\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED_MESSAGE))
                .andExpect(jsonPath("$.repositories", hasSize(3)))
                .andExpect(jsonPath("$.repositories[0].id").value(501L))
                .andExpect(jsonPath("$.repositories[0].stars").value(100))
                .andExpect(jsonPath("$.repositories[1].stars").value(500))
                .andExpect(jsonPath("$.repositories[2].stars").value(900));

        assertThat(repository.count()).isEqualTo(3);

        postSearch("{\"query\": \"spring\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED_MESSAGE))
                .andExpect(jsonPath("$.repositories", hasSize(3)))
                .andExpect(jsonPath("$.repositories[0].stars").value(111))
                .andExpect(jsonPath("$.repositories[1].stars").value(555))
                .andExpect(jsonPath("$.repositories[2].stars").value(999));

        assertThat(repository.count()).isEqualTo(3);

        GithubRepository updated = repository.findById(501L).orElseThrow();
        assertThat(updated.getName()).isEqualTo("alpha");
        assertThat(updated.getStars()).isEqualTo(111);
        assertThat(updated.getForks()).isEqualTo(10);
    }

    @Test
    void getReadsStoredRowsWithFiltersAndSortWithoutCallingGithub() throws Exception {
        when(client.search(anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(cannedResponse(100, 500, 900));
        postSearch("{\"query\": \"spring\"}").andExpect(status().isOk());

        // Everything after this point is a pure read: the client must stay untouched.
        clearInvocations(client);

        mockMvc.perform(get("/api/github/repositories")
                        .param("language", "Java")
                        .param("minStars", "100")
                        .param("sort", "stars")
                        .param("order", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories", hasSize(2)))
                .andExpect(jsonPath("$.repositories[0].id").value(502L))
                .andExpect(jsonPath("$.repositories[1].id").value(501L))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));

        mockMvc.perform(get("/api/github/repositories").param("sort", "stars").param("order", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories", hasSize(3)))
                .andExpect(jsonPath("$.repositories[0].id").value(501L))
                .andExpect(jsonPath("$.repositories[1].id").value(502L))
                .andExpect(jsonPath("$.repositories[2].id").value(503L));

        mockMvc.perform(get("/api/github/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories", hasSize(3)))
                .andExpect(jsonPath("$.repositories[0].language").value("Python"))
                .andExpect(jsonPath("$.repositories[0].stars").value(900));

        verifyNoInteractions(client);
    }

    @Test
    void anInvalidSearchSortIsRejectedBeforeGithubIsCalled() throws Exception {
        postSearch("{\"query\": \"spring\", \"sort\": \"rating\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Invalid sort field 'rating'. Allowed values: stars, forks, updated"));

        verifyNoInteractions(client);
        assertThat(repository.count()).isZero();
    }

    @Test
    void anEmptyGithubResultReturnsTheEmptyMessageAndSavesNothing() throws Exception {
        when(client.search(anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new GithubSearchResponse(0, false, List.of()));

        postSearch("{\"query\": \"nothing-matches-this\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(EMPTY_MESSAGE))
                .andExpect(jsonPath("$.repositories").isEmpty());

        assertThat(repository.count()).isZero();
    }

    private ResultActions postSearch(String json) throws Exception {
        return mockMvc.perform(post("/api/github/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static GithubSearchResponse cannedResponse(int alphaStars, int betaStars, int gammaStars) {
        return new GithubSearchResponse(3, false, List.of(
                item(501L, "alpha", "Java", alphaStars, 10),
                item(502L, "beta", "Java", betaStars, 20),
                item(503L, "gamma", "Python", gammaStars, 30)));
    }

    private static GithubRepositoryItem item(long id, String name, String language, int stars, int forks) {
        return new GithubRepositoryItem(
                id,
                name,
                name + " repository",
                new GithubOwner("owner-" + name),
                language,
                stars,
                forks,
                UPDATED_AT,
                "https://github.com/owner-" + name + "/" + name,
                3,
                CREATED_AT);
    }
}
