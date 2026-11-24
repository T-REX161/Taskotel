-- V29: remembers which assignments a Staff member made THEMSELVES by accepting an available task (Staff self-service claiming), so such a
-- task stays exclusively theirs: nobody else can claim it, and no one can be silently added to it (a Manager's multi-staff assignment, or
-- Administrator-confirmed cross-department support). A Manager may still reassign or unassign it explicitly.
--
-- Adds one column and nothing else: no existing row, constraint, index or table is changed or deleted.
--   assignments.self_claimed   BOOLEAN NOT NULL DEFAULT FALSE. Every existing row becomes FALSE (not self-claimed), so every task that is
--                              assigned today behaves exactly as before. Only a claim made after this release sets it to TRUE.
--                              On PostgreSQL 11 or later a constant default is a metadata-only change (no table rewrite, a very brief lock).
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after review and a rehearsal on a
-- disposable database:
--   psql -1 -v ON_ERROR_STOP=1 -f V29__assignment_self_claimed.sql
-- Deployment order: apply V29, then start the new backend. It must be applied first: the production profile runs Hibernate schema
-- validation and the assignment entity maps this column, so a backend started without it fails at startup. (There is no V28 in this release: that number is intentionally unused, and the previous release is V27.)
--
-- Rollback order: first roll the APPLICATION back to the previous version, and only then consider dropping the column. Dropping it while
-- the new backend is deployed breaks every assignment query. Loses only the "accepted by themselves" record; assignments are untouched.
--   ALTER TABLE assignments DROP COLUMN self_claimed;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.assignments', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V29: %.assignments does not exist. No changes were made.', current_schema();
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'assignments' AND column_name = 'self_claimed') THEN
        RAISE EXCEPTION 'V29: assignments.self_claimed already exists. Review it by hand first. No changes were made.';
    END IF;
END
$$;

ALTER TABLE assignments ADD COLUMN self_claimed BOOLEAN NOT NULL DEFAULT FALSE;
