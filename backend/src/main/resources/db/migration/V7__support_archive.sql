-- V7: let a manager remove an ended support record from the history list without deleting it.
--
-- Two nullable columns on support_assignments, nothing else: no row is read for change, updated or deleted, no constraint
-- or index changes, and every existing row stays valid (archived_at NULL means "shown in the history", as before).
-- Nothing else references this table (no foreign keys), so tasks, assignments, staff and departments are unaffected.
--
-- Run by hand in one transaction:  psql -1 -v ON_ERROR_STOP=1 -f V7__support_archive.sql
--
-- Rollback (loses only the archive marks; the records themselves stay):
--   ALTER TABLE support_assignments DROP COLUMN archived_by_username;
--   ALTER TABLE support_assignments DROP COLUMN archived_at;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.support_assignments', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V7: %.support_assignments does not exist. Apply V5 first. No changes were made.', current_schema();
    END IF;
END
$$;

ALTER TABLE support_assignments ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP;
ALTER TABLE support_assignments ADD COLUMN IF NOT EXISTS archived_by_username VARCHAR(255);
