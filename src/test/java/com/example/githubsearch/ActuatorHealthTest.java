package com.example.githubsearch;

import com.example.githubsearch.support.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the hosting contract: a health endpoint that reports UP, and an actuator surface that exposes
 * nothing else.
 *
 * <p>Deployment platforms poll {@code /actuator/health}. Exposing only {@code health} is a security decision:
 * endpoints such as {@code env} or {@code beans} would leak configuration to anyone who can reach the API.
 */
@SpringBootTest(classes = GithubRepositorySearcherApplication.class)
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class ActuatorHealthTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void onlyHealthIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    }
}
