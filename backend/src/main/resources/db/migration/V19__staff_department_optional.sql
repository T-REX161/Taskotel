-- V19: any Staff record (a STAFF, MANAGER or ADMIN account) may have no department yet (an Administrator assigns one later).
--
-- Drops NOT NULL on staff.department and changes nothing else: no row is read for change, updated or deleted, so every existing Staff record
-- keeps exactly the department it has now. The foreign key staff -> departments(code) (V4, or V21 on a Hibernate-built schema) accepts NULL, and tasks.department is not
-- touched (a task may already have no department). The application keeps the rules that matter: a Manager with no department gets no
-- department-scoped access until an Admin assigns one, and a Staff member with no department can never be assigned a task or lent to another department.
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after review:
--   psql -1 -v ON_ERROR_STOP=1 -f V19__staff_department_optional.sql
-- Running it again is harmless (dropping NOT NULL from a column that is already nullable does nothing).
--
-- ROLLBACK WARNING: restoring NOT NULL (ALTER TABLE staff ALTER COLUMN department SET NOT NULL;) is possible ONLY while no Staff record has a null department.
-- Find them first with:  SELECT id, name FROM staff WHERE department IS NULL;  and give each a department, or the statement fails.

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.staff', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V19: %.staff does not exist. Apply V1 first. No changes were made.', current_schema();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = 'staff' AND column_name = 'department') THEN
        RAISE EXCEPTION 'V19: %.staff has no department column. No changes were made.', current_schema();
    END IF;
END
$$;

ALTER TABLE staff ALTER COLUMN department DROP NOT NULL;
