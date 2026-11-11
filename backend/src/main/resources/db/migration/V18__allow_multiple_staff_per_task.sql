-- V18: allow several staff members on one task (POST /api/assignments with staffIds).
--
-- V3 added UNIQUE (task_id) on assignments, which allows only one assignment row per task. This replaces it with UNIQUE (task_id, staff_id):
-- one task may now have several rows, but the same staff member cannot be assigned to the same task twice.
--   1. DROP CONSTRAINT uk_assignments_task_id   (the V3 rule; it is only dropped if it has the exact V3 definition; a database that never
--                                                had it, for example one built by Hibernate in development, simply skips this step)
--   2. ADD CONSTRAINT uk_assignments_task_staff UNIQUE (task_id, staff_id)
-- No row is read for change, updated or deleted: every existing assignment row stays exactly as it is. Because V3 allowed at most one row per
-- task, no (task_id, staff_id) pair can repeat, so the new constraint always succeeds on a V3 database; on any other database the script
-- refuses to run when a pair already repeats.
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after review:
--   psql -1 -v ON_ERROR_STOP=1 -f V18__allow_multiple_staff_per_task.sql
--
-- Rollback (possible only while no task has more than one assignment row; otherwise the second statement fails and nothing changes):
--   ALTER TABLE assignments DROP CONSTRAINT uk_assignments_task_staff;
--   ALTER TABLE assignments ADD CONSTRAINT uk_assignments_task_id UNIQUE (task_id);

SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    v_schema      text := current_schema();
    v_assignments regclass;
    v_def         text;
    v_pairs       bigint;
BEGIN
    v_assignments := to_regclass(format('%I.assignments', v_schema));
    IF v_assignments IS NULL THEN
        RAISE EXCEPTION 'V18: %.assignments does not exist. No changes were made.', v_schema;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = v_assignments AND conname = 'uk_assignments_task_staff') THEN
        RAISE EXCEPTION 'V18: uk_assignments_task_staff already exists on %.assignments. Review it by hand first. No changes were made.', v_schema;
    END IF;

    SELECT pg_get_constraintdef(oid) INTO v_def
      FROM pg_constraint
     WHERE conrelid = v_assignments AND conname = 'uk_assignments_task_id';
    IF v_def IS NOT NULL AND v_def <> 'UNIQUE (task_id)' THEN
        RAISE EXCEPTION 'V18: uk_assignments_task_id has an unexpected definition (%). Review it by hand first. No changes were made.', v_def;
    END IF;

    EXECUTE format('SELECT count(*) FROM (SELECT 1 FROM %I.assignments GROUP BY task_id, staff_id HAVING count(*) > 1) pairs', v_schema)
       INTO v_pairs;
    IF v_pairs > 0 THEN
        RAISE EXCEPTION 'V18: % staff/task pair(s) already appear more than once in %.assignments. Review them by hand first. No changes were made.', v_pairs, v_schema;
    END IF;
END
$$;

ALTER TABLE assignments DROP CONSTRAINT IF EXISTS uk_assignments_task_id;

ALTER TABLE assignments ADD CONSTRAINT uk_assignments_task_staff UNIQUE (task_id, staff_id);

COMMENT ON CONSTRAINT uk_assignments_task_staff ON assignments IS 'Created by Flyway V18: a staff member can be assigned to a task once; a task may have several staff members';
