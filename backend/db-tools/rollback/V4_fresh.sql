-- Rollback of V4 on a FRESH installation (one where Flyway ran V1, V2, V3 and V4).
-- Run manually and only with approval:
--   psql -v ON_ERROR_STOP=1 -h <host> -U <user> -d <database> -f V4_fresh.sql
--
-- Returns the schema to its state after V3: removes the departments table, its indexes and the foreign
-- keys staff.department / tasks.department, and removes the version-4 row from flyway_schema_history.
-- A fresh installation never had the enum CHECK constraints (V1 does not create them), so none are added back.
-- Roll back V4 first, then V3_fresh.sql if V3 must go too.
-- No staff, task, assignment or user row is touched.
--
-- Changes NOTHING unless every check passes:
--   1. public.flyway_schema_history holds exactly four successful entries, in order:
--      1:SQL, 2:SQL, 3:SQL, 4:SQL (a baselined installation must use V4_existing.sql instead).
--   2. public.departments exists and carries the V4 marker comment.
--   3. It holds exactly the six original departments, none of them edited (name, icon, colour, active,
--      description, department head), so rolling back loses no information.
--   4. Every staff and task department code is one of the six original codes.
-- Any failed check raises an exception, which aborts the transaction: nothing changes.

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
    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'No public.flyway_schema_history table: this database was never migrated by Flyway. Nothing changed.';
    END IF;
    SELECT string_agg(coalesce(version, '<null>') || ':' || type || ':' || success, ',' ORDER BY installed_rank)
      INTO v_history FROM public.flyway_schema_history;
    IF v_history IS DISTINCT FROM '1:SQL:true,2:SQL:true,3:SQL:true,4:SQL:true' THEN
        RAISE EXCEPTION 'Expected flyway_schema_history to be exactly 1:SQL:true,2:SQL:true,3:SQL:true,4:SQL:true for a fresh installation but found %. Use V4_existing.sql for baselined installations. Nothing changed.',
            coalesce(v_history, '<empty>');
    END IF;

    IF to_regclass('public.departments') IS NULL THEN
        RAISE EXCEPTION 'public.departments is not present although V4 is recorded as applied. Review manually. Nothing changed.';
    END IF;
    SELECT obj_description('public.departments'::regclass, 'pg_class') INTO v_comment;
    IF v_comment IS DISTINCT FROM 'Created by Flyway V4: departments table (replaces the department enum)' THEN
        RAISE EXCEPTION 'public.departments exists but was not created by V4. Refusing to drop it. Nothing changed.';
    END IF;

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

    SELECT (SELECT count(*) FROM public.staff WHERE department <> ALL (v_codes))
         + (SELECT count(*) FROM public.tasks WHERE department IS NOT NULL AND department <> ALL (v_codes))
      INTO v_outside;
    IF v_outside > 0 THEN
        RAISE EXCEPTION '% staff/task row(s) use a department outside the original six. Nothing changed.', v_outside;
    END IF;

    ALTER TABLE public.staff DROP CONSTRAINT fk_staff_department_code;
    ALTER TABLE public.tasks DROP CONSTRAINT fk_tasks_department_code;
    DROP INDEX public.idx_staff_department;
    DROP INDEX public.idx_tasks_department;
    DROP TABLE public.departments;
    DELETE FROM public.flyway_schema_history WHERE version = '4';
    RAISE NOTICE 'Rolled back V4: departments table removed and the V4 history row removed.';
END
$$;

COMMIT;
