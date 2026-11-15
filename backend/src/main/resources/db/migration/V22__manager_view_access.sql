-- V22: Admin-controlled view-only access for Managers to additional departments.
--
-- Adds two new tables and their indexes, nothing else: no existing table, row, constraint or column is changed or deleted.
--   manager_view_access          one row per (Manager account, department) the Manager may VIEW in addition to their own department.
--                                Foreign keys to users and departments with ON DELETE CASCADE, so a grant never outlives either.
--                                A grant gives no management right: write authorization is decided by the primary department only.
--   manager_view_access_changes  append-only audit of grants and revocations (plain identifiers with name snapshots, no foreign keys).
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after review:
--   psql -1 -v ON_ERROR_STOP=1 -f V22__manager_view_access.sql
--
-- Rollback (removes every grant, so Managers see their own department only, and the grant history):
--   DROP TABLE manager_view_access_changes; DROP TABLE manager_view_access;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.manager_view_access', current_schema())) IS NOT NULL
       OR to_regclass(format('%I.manager_view_access_changes', current_schema())) IS NOT NULL THEN
        RAISE EXCEPTION 'V22: %.manager_view_access or manager_view_access_changes already exists. Review it by hand first. No changes were made.', current_schema();
    END IF;
END
$$;

CREATE TABLE manager_view_access (
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

-- The unique constraint's index serves lookups by manager; this one serves the department side of the cascade
CREATE INDEX ix_manager_view_access_department ON manager_view_access (department_id);

CREATE TABLE manager_view_access_changes (
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

CREATE INDEX ix_mgr_view_changes_manager ON manager_view_access_changes (manager_user_id, changed_at);
