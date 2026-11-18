package com.example.githubsearch.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapts Neon's {@code postgresql://} connection strings to the JDBC properties Spring Boot expects.
 *
 * <p>Neon (like most managed providers) hands out a URI of the form
 * {@code postgresql://role:password@host/database?sslmode=require}. The PostgreSQL JDBC driver cannot
 * consume that form: it needs {@code jdbc:postgresql://host:5432/database} plus a separate username and
 * password. Doing the conversion here keeps {@code application.yml} provider-neutral and keeps the
 * password out of the image and the repository.
 *
 * <p>Two URLs are read when present. {@code DATABASE_URL} is Neon's pooled endpoint and is used for normal
 * request traffic; {@code DATABASE_URL_UNPOOLED} is the direct endpoint and is given to Flyway, because
 * schema migrations must not run through PgBouncer's transaction pooling. A Neon URI also carries
 * {@code channel_binding=require}, which the JDBC driver does not understand, so only {@code sslmode} is
 * carried across.
 *
 * <p>An explicit {@code DB_URL} always wins. That preserves the existing local workflow and lets a host
 * such as Render override the database without touching this code. The class is registered in
 * {@code META-INF/spring.factories}, so Spring Boot runs it before the DataSource is created.
 */
public class NeonEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String DB_URL = "DB_URL";
    static final String DATABASE_URL = "DATABASE_URL";
    static final String DATABASE_URL_UNPOOLED = "DATABASE_URL_UNPOOLED";

    private static final int POSTGRESQL_DEFAULT_PORT = 5432;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (StringUtils.hasText(environment.getProperty(DB_URL))) {
            return;
        }

        String pooled = environment.getProperty(DATABASE_URL);
        String direct = environment.getProperty(DATABASE_URL_UNPOOLED);
        if (!StringUtils.hasText(pooled) && !StringUtils.hasText(direct)) {
            return;
        }

        Connection runtime = parse(StringUtils.hasText(pooled) ? pooled : direct, DATABASE_URL);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", runtime.jdbcUrl());
        properties.put("spring.datasource.username", runtime.username());
        properties.put("spring.datasource.password", runtime.password());

        if (StringUtils.hasText(direct)) {
            Connection migration = parse(direct, DATABASE_URL_UNPOOLED);
            properties.put("spring.flyway.url", migration.jdbcUrl());
            properties.put("spring.flyway.user", migration.username());
            properties.put("spring.flyway.password", migration.password());
        }

        environment.getPropertySources().addFirst(new MapPropertySource("neonDatabase", properties));
    }

    @Override
    public int getOrder() {
        // Run after config data (application.yml) has been loaded, before the DataSource is bound.
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static Connection parse(String value, String variable) {
        String normalized = unquote(value.trim());
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            // Never include the value: it contains the password.
            throw new IllegalStateException(variable + " is not a valid URL");
        }

        String scheme = uri.getScheme();
        if (!"postgres".equalsIgnoreCase(scheme) && !"postgresql".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException(variable + " must use the postgres or postgresql scheme");
        }

        String userInfo = uri.getUserInfo();
        int separator = userInfo == null ? -1 : userInfo.indexOf(':');
        if (separator < 1) {
            throw new IllegalStateException(variable + " must contain a username and password");
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IllegalStateException(variable + " must contain a host");
        }
        if (host.indexOf(':') >= 0) {
            host = "[" + host + "]";
        }

        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (!StringUtils.hasText(database)) {
            throw new IllegalStateException(variable + " must contain a database name");
        }

        int port = uri.getPort() == -1 ? POSTGRESQL_DEFAULT_PORT : uri.getPort();
        String sslMode = queryParameter(uri.getRawQuery(), "sslmode");

        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database
                + "?sslmode=" + (StringUtils.hasText(sslMode) ? sslMode : "require");

        return new Connection(
                jdbcUrl,
                decode(userInfo.substring(0, separator)),
                decode(userInfo.substring(separator + 1)));
    }

    /**
     * Removes matching single or double quotes. {@code neon env pull} writes quoted values into
     * {@code .env}, while Docker's {@code --env-file} passes those quotes through verbatim.
     */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /**
     * Percent-decodes a URI component without treating {@code +} as a space. Passwords may legitimately
     * contain a plus sign, and URLDecoder's form mode would corrupt it.
     */
    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String queryParameter(String rawQuery, String name) {
        if (!StringUtils.hasText(rawQuery)) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(pair.substring(0, equals))) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    private record Connection(String jdbcUrl, String username, String password) {
    }
}
