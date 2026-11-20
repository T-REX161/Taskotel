-- H2 stand-in for Flyway V25 (role management). V25 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the
-- production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead. Same column, table, types and constraints.
ALTER TABLE users ADD COLUMN IF NOT EXISTS sessions_valid_after TIMESTAMP;

CREATE TABLE IF NOT EXISTS user_role_changes (
    id                   UUID         PRIMARY KEY,
    target_user_id       UUID         NOT NULL,
    target_username      VARCHAR(255) NOT NULL,
    previous_role        VARCHAR(16)  NOT NULL,
    new_role             VARCHAR(16)  NOT NULL,
    revoked_grants       INTEGER      NOT NULL DEFAULT 0,
    changed_by_user_id   UUID         NOT NULL,
    changed_by_username  VARCHAR(255) NOT NULL,
    changed_at           TIMESTAMP    NOT NULL,
    CONSTRAINT ck_user_role_changes_roles CHECK (previous_role IN ('ADMIN', 'MANAGER', 'STAFF') AND new_role IN ('ADMIN', 'MANAGER', 'STAFF') AND previous_role <> new_role)
);

CREATE INDEX IF NOT EXISTS ix_user_role_changes_target ON user_role_changes (target_user_id, changed_at);
