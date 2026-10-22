-- Rollback of V3 on a FRESH installation (one where Flyway ran V1, V2 and V3).
-- Run manually and only with approval:
--   psql -v ON_ERROR_STOP=1 -h <host> -U <user> -d <database> -f V3_fresh.sql
--
-- Returns the schema to its state after V2: V3 added only the constraint uk_assignments_task_id,
-- so this drops that constraint (only if V3 created it) and removes the V3 history row.
-- V1, V2 and their history rows are kept. No rows in application tables are touched.
--
-- Changes NOTHING unless every check passes:
--   1. public.flyway_schema_history holds exactly three successful entries, in order:
--      1:SQL:true, 2:SQL:true, 3:SQL:true. A missing, failed, extra or out-of-order entry is
--      refused (a baselined installation must use V3_existing.sql instead).
--   2. public.assignments has uk_assignments_task_id carrying the exact V3 marker comment.
-- Any failed check raises an exception, which aborts the transaction: the schema and the history
-- stay exactly as they were. Both changes happen inside the same guarded block, constraint first.

BEGIN;

DO $$
DECLARE
    v_versions   text;
    v_found      boolean;
    v_comment    text;
BEGIN
    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'No public.flyway_schema_history table: this database was never migrated by Flyway. Nothing changed.';
    END IF;

    -- Exact history: successful V1, V2, V3 in installation order, nothing else
    SELECT string_agg(coalesce(version, '<null>') || ':' || type || ':' || success, ',' ORDER BY installed_rank)
      INTO v_versions
      FROM public.flyway_schema_history;
    IF v_versions IS DISTINCT FROM '1:SQL:true,2:SQL:true,3:SQL:true' THEN
        RAISE EXCEPTION 'Expected flyway_schema_history to be exactly 1:SQL:true,2:SQL:true,3:SQL:true for a fresh installation but found %. Use V3_existing.sql for baselined installations. Nothing changed.',
            coalesce(v_versions, '<empty>');
    END IF;

    SELECT true, obj_description(oid, 'pg_constraint') INTO v_found, v_comment
      FROM pg_constraint
     WHERE conname = 'uk_assignments_task_id' AND conrelid = 'public.assignments'::regclass;

    IF v_found IS NULL THEN
        RAISE EXCEPTION 'uk_assignments_task_id is not present although V3 is recorded as applied. Review manually. Nothing changed.';
    ELSIF v_comment IS DISTINCT FROM 'Created by Flyway V3: one assignment row per task' THEN
        RAISE EXCEPTION 'uk_assignments_task_id exists but was not created by V3. Refusing to drop it. Nothing changed.';
    END IF;

    ALTER TABLE public.assignments DROP CONSTRAINT uk_assignments_task_id;
    DELETE FROM public.flyway_schema_history WHERE version = '3';
    RAISE NOTICE 'Dropped uk_assignments_task_id and removed the V3 history row.';
END
$$;

COMMIT;
