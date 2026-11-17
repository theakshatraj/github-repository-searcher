-- V1: the single table this service needs.
--
-- id is GitHub's repository id. It is already unique and stable, so it is the primary key: no surrogate
-- key and no separate unique constraint to keep in sync. One caveat: if repositories from a second source
-- were ever stored here, this key would collide - not a requirement of this assignment.
--
-- synced_at records when *we* last wrote the row. It is owned by the database (default now(), refreshed by
-- the upsert), which is what makes an update visible to tests.
CREATE TABLE github_repositories (
    id                BIGINT        PRIMARY KEY,
    name              VARCHAR(255)  NOT NULL,
    description       TEXT,
    owner             VARCHAR(255)  NOT NULL,
    language          VARCHAR(100),
    stars             INTEGER       NOT NULL DEFAULT 0,
    forks             INTEGER       NOT NULL DEFAULT 0,
    last_updated      TIMESTAMPTZ,
    html_url          VARCHAR(500),
    open_issues_count INTEGER       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ,
    synced_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Functional index so `where lower(language) = 'java'` can use an index, giving case-insensitive filtering.
CREATE INDEX idx_repo_language_lower ON github_repositories (LOWER(language));
CREATE INDEX idx_repo_stars          ON github_repositories (stars DESC);
CREATE INDEX idx_repo_forks          ON github_repositories (forks DESC);
CREATE INDEX idx_repo_last_updated   ON github_repositories (last_updated DESC);
