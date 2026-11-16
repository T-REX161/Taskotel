-- V23: Administrator-granted additional departments become FULL management for the Manager (they were view-only under V22).
--
-- The permission change itself is in the application release; this script only brings the audit trail up to date so that the history shows
-- exactly which grants were upgraded, and by what. It changes no grant: manager_view_access is only read.
--
--   1. manager_view_access_changes: the action CHECK is widened to allow 'UPGRADED' (and is created if the database never had it).
--   2. manager_view_access_changes: the (manager, changed_at) index is created if it is missing.
--   3. One 'UPGRADED' audit row is written for every existing grant that has none yet: actor = the zero UUID, username
--      'SYSTEM (V23 migration)', source 'ACCESS_MODEL_V23', with the Manager's and the department's current name snapshots.
--      Existing audit rows are never updated or deleted.
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. It is part of ONE coordinated release together with the permission
-- change and the frontend; never deploy any of the three alone. Run by hand in one transaction, after review (and after a rehearsal on a
-- disposable copy of the database):
--   psql -1 -v ON_ERROR_STOP=1 -f V23__manager_view_access_full_management.sql
--
-- Safe to run again: a second run finds every grant already upgraded, writes nothing, and leaves the constraint and index as they are.
--
-- Rollback (removes only what this script added; the grants and every earlier audit row are untouched):
--   DELETE FROM manager_view_access_changes WHERE action = 'UPGRADED' AND change_source = 'ACCESS_MODEL_V23';
--   ALTER TABLE manager_view_access_changes DROP CONSTRAINT ck_manager_view_access_changes_action;
--   ALTER TABLE manager_view_access_changes ADD CONSTRAINT ck_manager_view_access_changes_action CHECK (action IN ('GRANTED', 'REVOKED'));

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.manager_view_access', current_schema())) IS NULL
       OR to_regclass(format('%I.manager_view_access_changes', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V23: %.manager_view_access or manager_view_access_changes does not exist (V22 has not been applied here). No changes were made.', current_schema();
    END IF;
    IF to_regclass(format('%I.users', current_schema())) IS NULL OR to_regclass(format('%I.departments', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V23: %.users or departments does not exist. No changes were made.', current_schema();
    END IF;
    -- Every existing audit row must already be one this script (or V22's model) can explain; anything else needs a human look first
    IF EXISTS (SELECT 1 FROM manager_view_access_changes WHERE action NOT IN ('GRANTED', 'REVOKED', 'UPGRADED')) THEN
        RAISE EXCEPTION 'V23: manager_view_access_changes holds an action other than GRANTED, REVOKED or UPGRADED. Review it by hand first. No changes were made.';
    END IF;
END
$$;

-- 1. The action CHECK, now allowing UPGRADED (also added where an older database never had it)
ALTER TABLE manager_view_access_changes DROP CONSTRAINT IF EXISTS ck_manager_view_access_changes_action;
ALTER TABLE manager_view_access_changes
    ADD CONSTRAINT ck_manager_view_access_changes_action CHECK (action IN ('GRANTED', 'REVOKED', 'UPGRADED'));

-- 2. The index that serves the history of one Manager
CREATE INDEX IF NOT EXISTS ix_mgr_view_changes_manager ON manager_view_access_changes (manager_user_id, changed_at);

-- 3. One UPGRADED row per grant that has none
INSERT INTO manager_view_access_changes
    (id, manager_user_id, manager_username, department_id, department_code, department_name,
     action, change_source, changed_by_user_id, changed_by_username, changed_at)
SELECT gen_random_uuid(), g.manager_user_id, u.username, g.department_id, d.code, d.name,
       'UPGRADED', 'ACCESS_MODEL_V23', '00000000-0000-0000-0000-000000000000'::uuid, 'SYSTEM (V23 migration)', LOCALTIMESTAMP
FROM manager_view_access g
JOIN users u ON u.id = g.manager_user_id
JOIN departments d ON d.id = g.department_id
WHERE NOT EXISTS (
    SELECT 1 FROM manager_view_access_changes c
    WHERE c.manager_user_id = g.manager_user_id
      AND c.department_id = g.department_id
      AND c.action = 'UPGRADED'
      AND c.change_source = 'ACCESS_MODEL_V23');
