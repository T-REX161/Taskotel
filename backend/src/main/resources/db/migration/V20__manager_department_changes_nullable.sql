-- V20: the Manager department audit history can record a first assignment (no previous department) and a removal (no new department).
--
-- Drops NOT NULL on the four department snapshot columns of manager_department_changes (previous and new code and name) and changes nothing
-- else: no row is read for change, updated or deleted, no other column, constraint or index is touched, and the two department id columns
-- are already nullable (V15). Every existing record keeps exactly the values it has now. A null side means "Unassigned".
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after V15 and review:
--   psql -1 -v ON_ERROR_STOP=1 -f V20__manager_department_changes_nullable.sql
-- Running it again is harmless (dropping NOT NULL from a column that is already nullable does nothing).
--
-- ROLLBACK WARNING: restoring NOT NULL on these columns is possible ONLY while no audit record has a null in them. Find them first with:
--   SELECT id FROM manager_department_changes WHERE previous_department_code IS NULL OR new_department_code IS NULL;
-- Those records are the first assignments and removals this migration makes possible; deleting them would lose audit history.

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.manager_department_changes', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V20: %.manager_department_changes does not exist. Apply V15 first. No changes were made.', current_schema();
    END IF;
END
$$;

ALTER TABLE manager_department_changes ALTER COLUMN previous_department_code DROP NOT NULL;
ALTER TABLE manager_department_changes ALTER COLUMN previous_department_name DROP NOT NULL;
ALTER TABLE manager_department_changes ALTER COLUMN new_department_code DROP NOT NULL;
ALTER TABLE manager_department_changes ALTER COLUMN new_department_name DROP NOT NULL;
