-- V15: audit history of Manager department changes (RBAC Decision 17.6).
--
-- Adds one new table and an index on it, nothing else: no existing table, row, constraint or column is read for change, updated or
-- deleted. Rows are only ever inserted, by the Admin operation that changes a Manager department (and by an Admin staff edit that
-- moves a Manager staff record). Users, staff and departments are plain identifiers with name snapshots (no foreign keys), so the
-- history stays valid and readable after a department is renamed or an account is removed.
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after review:
--   psql -1 -v ON_ERROR_STOP=1 -f V15__manager_department_changes.sql
--
-- Rollback (loses only the audit history): DROP TABLE manager_department_changes;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.manager_department_changes', current_schema())) IS NOT NULL THEN
        RAISE EXCEPTION 'V15: %.manager_department_changes already exists. Review it by hand first. No changes were made.', current_schema();
    END IF;
END
$$;

CREATE TABLE manager_department_changes (
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

CREATE INDEX ix_mgr_dept_changes_manager ON manager_department_changes (manager_user_id, changed_at);
