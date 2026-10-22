-- Rollback of V3 on an EXISTING installation (one that Flyway baselined at version 2).
-- Run manually and only with approval:
--   psql -v ON_ERROR_STOP=1 -h <host> -U <user> -d <database> -f V3_existing.sql
--
-- Returns the schema to its exact pre-Flyway state. On a baselined installation, Flyway created
-- only flyway_schema_history (a version-2 BASELINE row plus the version-3 SQL row) and V3 added
-- only the constraint uk_assignments_task_id. No rows in application tables are touched.
--
-- Changes NOTHING unless every check passes:
--   1. public.flyway_schema_history exists and holds exactly two successful entries, in order:
--      version 2 BASELINE and version 3 SQL. Anything missing, extra or failed is refused, so the
--      baseline history is never erased when V3 was not applied or already rolled back.
--   2. public.assignments has uk_assignments_task_id carrying the exact V3 marker comment.
-- Any failed check raises an exception, which aborts the transaction: the schema and the history
-- stay exactly as they were. Both changes happen inside the same guarded block, constraint first.

BEGIN;

DO $$
DECLARE
    v_history    text;
    v_found      boolean;
    v_comment    text;
BEGIN
    -- 1. Exact history: version-2 baseline followed by a successful V3, nothing else
    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'No public.flyway_schema_history table: this database was never migrated by Flyway. Nothing changed.';
    END IF;

    SELECT string_agg(coalesce(version, '<null>') || ':' || type || ':' || success, ',' ORDER BY installed_rank)
      INTO v_history
      FROM public.flyway_schema_history;
    IF v_history IS DISTINCT FROM '2:BASELINE:true,3:SQL:true' THEN
        RAISE EXCEPTION 'Expected flyway_schema_history to be exactly 2:BASELINE:true,3:SQL:true but found %. Nothing changed.',
            coalesce(v_history, '<empty>');
    END IF;

    -- 2. The constraint exists on public.assignments and was created by V3
    SELECT true, obj_description(oid, 'pg_constraint') INTO v_found, v_comment
      FROM pg_constraint
     WHERE conname = 'uk_assignments_task_id' AND conrelid = 'public.assignments'::regclass;
    IF v_found IS NULL THEN
        RAISE EXCEPTION 'uk_assignments_task_id is not present on public.assignments although V3 is recorded as applied. Review manually. Nothing changed.';
    END IF;
    IF v_comment IS DISTINCT FROM 'Created by Flyway V3: one assignment row per task' THEN
        RAISE EXCEPTION 'uk_assignments_task_id exists but was not created by V3. Refusing to drop it. Nothing changed.';
    END IF;

    -- 3. All checks passed: remove the constraint first, then the history table
    ALTER TABLE public.assignments DROP CONSTRAINT uk_assignments_task_id;
    DROP TABLE public.flyway_schema_history;
    RAISE NOTICE 'Dropped uk_assignments_task_id and public.flyway_schema_history; the schema is back to its pre-Flyway state.';
END
$$;

COMMIT;
