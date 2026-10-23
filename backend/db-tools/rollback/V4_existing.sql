-- Rollback of V4 on an EXISTING installation (one that Flyway baselined at version 2, then applied V3 and V4).
-- Run manually and only with approval:
--   psql -v ON_ERROR_STOP=1 -h <host> -U <user> -d <database> -f V4_existing.sql
--
-- Returns the schema to its state after V3, which for a database built by Hibernate means:
--   * the departments table, its indexes and the foreign keys staff.department / tasks.department are removed;
--   * the two enum CHECK constraints staff_department_check and tasks_department_check are put back,
--     with the same definition Hibernate generated;
--   * the version-4 row is removed from flyway_schema_history.
-- Roll back V4 first, then V3_existing.sql if V3 must go too.
-- No staff, task, assignment or user row is touched: staff.department and tasks.department keep their values.
--
-- Changes NOTHING unless every check passes:
--   1. public.flyway_schema_history holds exactly three successful entries, in order:
--      2:BASELINE, 3:SQL, 4:SQL.
--   2. public.departments exists and carries the V4 marker comment.
--   3. It holds exactly the six original departments, none of them edited (name, icon, colour, active,
--      description, department head), so rolling back loses no information. A department added or edited
--      after V4 makes this script refuse.
--   4. Every staff and task department code is one of the six original codes.
-- Any failed check raises an exception, which aborts the transaction: the schema and the history stay
-- exactly as they were.

BEGIN;

DO $$
DECLARE
    v_history  text;
    v_comment  text;
    v_codes    text[] := ARRAY['HOUSEKEEPING', 'FRONT_DESK', 'MAINTENANCE', 'ROOM_SERVICE', 'FOOD_AND_BEVERAGE', 'SECURITY'];
    v_count    bigint;
    v_edited   bigint;
    v_outside  bigint;
BEGIN
    -- 1. Exact history
    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'No public.flyway_schema_history table: this database was never migrated by Flyway. Nothing changed.';
    END IF;
    SELECT string_agg(coalesce(version, '<null>') || ':' || type || ':' || success, ',' ORDER BY installed_rank)
      INTO v_history FROM public.flyway_schema_history;
    IF v_history IS DISTINCT FROM '2:BASELINE:true,3:SQL:true,4:SQL:true' THEN
        RAISE EXCEPTION 'Expected flyway_schema_history to be exactly 2:BASELINE:true,3:SQL:true,4:SQL:true but found %. Nothing changed.',
            coalesce(v_history, '<empty>');
    END IF;

    -- 2. The table was created by V4
    IF to_regclass('public.departments') IS NULL THEN
        RAISE EXCEPTION 'public.departments is not present although V4 is recorded as applied. Review manually. Nothing changed.';
    END IF;
    SELECT obj_description('public.departments'::regclass, 'pg_class') INTO v_comment;
    IF v_comment IS DISTINCT FROM 'Created by Flyway V4: departments table (replaces the department enum)' THEN
        RAISE EXCEPTION 'public.departments exists but was not created by V4. Refusing to drop it. Nothing changed.';
    END IF;

    -- 3. Only the six original, unedited departments
    SELECT count(*) INTO v_count FROM public.departments;
    IF v_count <> 6 OR EXISTS (SELECT 1 FROM public.departments WHERE code <> ALL (v_codes)) THEN
        RAISE EXCEPTION 'public.departments holds % row(s) that are not exactly the six original departments. Rolling back would lose departments. Nothing changed.', v_count;
    END IF;
    SELECT count(*) INTO v_edited FROM public.departments d
     WHERE d.description IS NOT NULL OR d.head_staff_id IS NOT NULL OR NOT d.active
        OR (d.code, d.name, d.icon_key, d.color_key) NOT IN (
              ('HOUSEKEEPING', 'Housekeeping', 'sprout', 'green'),
              ('FRONT_DESK', 'Front Desk', 'bell', 'blue'),
              ('MAINTENANCE', 'Maintenance', 'wrench', 'purple'),
              ('ROOM_SERVICE', 'Room Service', 'coffee', 'amber'),
              ('FOOD_AND_BEVERAGE', 'Food & Beverage', 'utensils', 'orange'),
              ('SECURITY', 'Security', 'shield', 'cyan'));
    IF v_edited > 0 THEN
        RAISE EXCEPTION '% original department(s) were edited after V4 (name, icon, colour, status, description or head). Rolling back would lose those changes. Nothing changed.', v_edited;
    END IF;

    -- 4. No staff or task uses a code outside the original six
    SELECT (SELECT count(*) FROM public.staff WHERE department <> ALL (v_codes))
         + (SELECT count(*) FROM public.tasks WHERE department IS NOT NULL AND department <> ALL (v_codes))
      INTO v_outside;
    IF v_outside > 0 THEN
        RAISE EXCEPTION '% staff/task row(s) use a department outside the original six. Nothing changed.', v_outside;
    END IF;

    -- All checks passed: remove V4's objects, restore the enum CHECK constraints, remove the history row
    ALTER TABLE public.staff DROP CONSTRAINT fk_staff_department_code;
    ALTER TABLE public.tasks DROP CONSTRAINT fk_tasks_department_code;
    DROP INDEX public.idx_staff_department;
    DROP INDEX public.idx_tasks_department;

    ALTER TABLE public.staff ADD CONSTRAINT staff_department_check CHECK (((department)::text = ANY ((ARRAY['HOUSEKEEPING'::character varying, 'FRONT_DESK'::character varying, 'MAINTENANCE'::character varying, 'ROOM_SERVICE'::character varying, 'FOOD_AND_BEVERAGE'::character varying, 'SECURITY'::character varying])::text[])));
    ALTER TABLE public.tasks ADD CONSTRAINT tasks_department_check CHECK (((department)::text = ANY ((ARRAY['HOUSEKEEPING'::character varying, 'FRONT_DESK'::character varying, 'MAINTENANCE'::character varying, 'ROOM_SERVICE'::character varying, 'FOOD_AND_BEVERAGE'::character varying, 'SECURITY'::character varying])::text[])));

    DROP TABLE public.departments;
    DELETE FROM public.flyway_schema_history WHERE version = '4';
    RAISE NOTICE 'Rolled back V4: departments table removed, enum CHECK constraints restored, V4 history row removed.';
END
$$;

COMMIT;
