-- H2 stand-in for Flyway V22 (Manager view-only department access). V22 itself uses PostgreSQL-only syntax (a DO block and
-- SET LOCAL), so the production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead.
-- Same tables, columns, types, constraints and indexes.
CREATE TABLE IF NOT EXISTS manager_view_access (
    id                   UUID         PRIMARY KEY,
    manager_user_id      UUID         NOT NULL,
    department_id        UUID         NOT NULL,
    granted_by_user_id   UUID         NOT NULL,
    granted_by_username  VARCHAR(255) NOT NULL,
    granted_at           TIMESTAMP    NOT NULL,
    CONSTRAINT uk_manager_view_access UNIQUE (manager_user_id, department_id),
    CONSTRAINT fk_manager_view_access_user FOREIGN KEY (manager_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_manager_view_access_department FOREIGN KEY (department_id) REFERENCES departments (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_manager_view_access_department ON manager_view_access (department_id);
CREATE TABLE IF NOT EXISTS manager_view_access_changes (
    id                   UUID         PRIMARY KEY,
    manager_user_id      UUID         NOT NULL,
    manager_username     VARCHAR(255) NOT NULL,
    department_id        UUID         NOT NULL,
    department_code      VARCHAR(64)  NOT NULL,
    department_name      VARCHAR(100) NOT NULL,
    action               VARCHAR(16)  NOT NULL,
    change_source        VARCHAR(40)  NOT NULL,
    changed_by_user_id   UUID         NOT NULL,
    changed_by_username  VARCHAR(255) NOT NULL,
    changed_at           TIMESTAMP    NOT NULL,
    CONSTRAINT ck_manager_view_access_changes_action CHECK (action IN ('GRANTED', 'REVOKED'))
);
CREATE INDEX IF NOT EXISTS ix_mgr_view_changes_manager ON manager_view_access_changes (manager_user_id, changed_at);
