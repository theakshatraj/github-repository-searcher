package com.example.githubsearch.repository;

import com.example.githubsearch.support.PostgresTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Flyway migration produced exactly the schema the blueprint specifies.
 *
 * <p>These assertions read {@code information_schema}/{@code pg_indexes} directly rather than trusting
 * Hibernate: the point is that <em>the database</em> has the columns, constraints and indexes, because
 * {@code ddl-auto=validate} would let a missing index pass unnoticed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig.class)
class DatabaseSchemaIT {

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void flywayCreatesTheRepositoryTable() {
        Integer tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'github_repositories'
                """, Integer.class);

        assertThat(tables).isEqualTo(1);
    }

    @Test
    void recordsVersionOneAsApplied() {
        List<String> versions = jdbc.queryForList("""
                select version from flyway_schema_history where success = true order by installed_rank
                """, String.class);

        assertThat(versions).containsExactly("1");
    }

    @Test
    void hasEveryPersistedColumnWithTheExpectedType() {
        Map<String, String> columns = new HashMap<>();
        jdbc.query("""
                select column_name, data_type from information_schema.columns
                where table_schema = 'public' and table_name = 'github_repositories'
                """, resultSet -> {
            columns.put(resultSet.getString("column_name"), resultSet.getString("data_type"));
        });

        assertThat(columns).containsOnlyKeys(
                "id", "name", "description", "owner", "language", "stars", "forks", "last_updated",
                "html_url", "open_issues_count", "created_at", "synced_at");

        assertThat(columns.get("id")).isEqualTo("bigint");
        assertThat(columns.get("name")).isEqualTo("character varying");
        assertThat(columns.get("description")).isEqualTo("text");
        assertThat(columns.get("owner")).isEqualTo("character varying");
        assertThat(columns.get("language")).isEqualTo("character varying");
        assertThat(columns.get("stars")).isEqualTo("integer");
        assertThat(columns.get("forks")).isEqualTo("integer");
        assertThat(columns.get("last_updated")).isEqualTo("timestamp with time zone");
        assertThat(columns.get("html_url")).isEqualTo("character varying");
        assertThat(columns.get("open_issues_count")).isEqualTo("integer");
        assertThat(columns.get("created_at")).isEqualTo("timestamp with time zone");
        assertThat(columns.get("synced_at")).isEqualTo("timestamp with time zone");
    }

    @Test
    void hasTheExpectedNullabilityAndDefaults() {
        Map<String, String> nullability = new HashMap<>();
        Map<String, String> defaults = new HashMap<>();
        jdbc.query("""
                select column_name, is_nullable, coalesce(column_default, '') as column_default
                from information_schema.columns
                where table_schema = 'public' and table_name = 'github_repositories'
                """, resultSet -> {
            nullability.put(resultSet.getString("column_name"), resultSet.getString("is_nullable"));
            defaults.put(resultSet.getString("column_name"), resultSet.getString("column_default"));
        });

        assertThat(nullability.get("id")).isEqualTo("NO");
        assertThat(nullability.get("name")).isEqualTo("NO");
        assertThat(nullability.get("owner")).isEqualTo("NO");
        assertThat(nullability.get("description")).isEqualTo("YES");
        assertThat(nullability.get("language")).isEqualTo("YES");
        assertThat(nullability.get("last_updated")).isEqualTo("YES");
        assertThat(nullability.get("html_url")).isEqualTo("YES");
        assertThat(nullability.get("created_at")).isEqualTo("YES");
        assertThat(nullability.get("open_issues_count")).isEqualTo("NO");
        assertThat(nullability.get("synced_at")).isEqualTo("NO");

        assertThat(defaults.get("stars")).contains("0");
        assertThat(defaults.get("forks")).contains("0");
        assertThat(defaults.get("open_issues_count")).contains("0");
        assertThat(defaults.get("synced_at")).contains("now()");
    }

    @Test
    void usesTheGithubIdAsTheSinglePrimaryKey() {
        List<String> primaryKeyColumns = jdbc.queryForList("""
                select kcu.column_name
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on tc.constraint_name = kcu.constraint_name and tc.table_schema = kcu.table_schema
                where tc.table_schema = 'public'
                  and tc.table_name = 'github_repositories'
                  and tc.constraint_type = 'PRIMARY KEY'
                """, String.class);

        assertThat(primaryKeyColumns).containsExactly("id");
    }

    @Test
    void createsTheFourIndexesIncludingTheLowercaseLanguageOne() {
        Map<String, String> indexDefinitions = new HashMap<>();
        jdbc.query("""
                select indexname, indexdef from pg_indexes
                where schemaname = 'public' and tablename = 'github_repositories'
                """, resultSet -> {
            indexDefinitions.put(resultSet.getString("indexname"), resultSet.getString("indexdef"));
        });

        assertThat(indexDefinitions).containsKeys(
                "idx_repo_language_lower", "idx_repo_stars", "idx_repo_forks", "idx_repo_last_updated");

        // PostgreSQL renders a functional index as lower((language)::text), so match the intent
        // (a lower() expression over language) rather than one particular spelling.
        assertThat(indexDefinitions.get("idx_repo_language_lower"))
                .containsIgnoringCase("lower(")
                .containsIgnoringCase("language");
        assertThat(indexDefinitions.get("idx_repo_stars")).containsIgnoringCase("stars desc");
        assertThat(indexDefinitions.get("idx_repo_forks")).containsIgnoringCase("forks desc");
        assertThat(indexDefinitions.get("idx_repo_last_updated")).containsIgnoringCase("last_updated desc");
    }
}
