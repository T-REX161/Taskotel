-- H2 stand-in for Flyway V15 (Manager department change audit history). V15 itself uses PostgreSQL-only syntax (a DO block and
-- SET LOCAL), so the production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead.
-- Same table, columns and types.
CREATE TABLE IF NOT EXISTS manager_department_changes (
    id                        UUID         PRIMARY KEY,
    manager_user_id           UUID         NOT NULL,
    manager_username          VARCHAR(255) NOT NULL,
    staff_id                  UUID         NOT NULL,
    staff_name                VARCHAR(255) NOT NULL,
    previous_department_id    UUID,
    previous_department_code  VARCHAR(64)  NOT NULL,
    previous_department_name  VARCHAR(100) NOT NULL,
    new_department_id         UUID,
    new_department_code       VARCHAR(64)  NOT NULL,
    new_department_name       VARCHAR(100) NOT NULL,
    changed_by_user_id        UUID         NOT NULL,
    changed_by_username       VARCHAR(255) NOT NULL,
    change_source             VARCHAR(40)  NOT NULL,
    changed_at                TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_mgr_dept_changes_manager ON manager_department_changes (manager_user_id, changed_at);
