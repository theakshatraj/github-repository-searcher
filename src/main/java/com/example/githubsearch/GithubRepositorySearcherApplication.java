package com.example.githubsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the GitHub Repository Searcher API.
 *
 * <p>{@code @SpringBootApplication} combines three things: {@code @Configuration} (this class can define
 * beans), {@code @EnableAutoConfiguration} (Spring Boot configures what the classpath implies, e.g. Tomcat,
 * Jackson, Hibernate and Flyway) and {@code @ComponentScan} (it scans this package and below for
 * {@code @Component}, {@code @Service}, {@code @RestController} and friends).
 *
 * <p>The package is {@code com.example.githubsearch}, so every future class must live in a sub-package
 * of it to be discovered.
 */
@SpringBootApplication
public class GithubRepositorySearcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(GithubRepositorySearcherApplication.class, args);
    }
}
