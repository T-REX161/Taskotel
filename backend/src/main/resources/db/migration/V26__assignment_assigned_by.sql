-- V26: remembers WHO made each assignment, so a completion email can reach the person who originally assigned the task.
--
-- Adds one nullable column and nothing else: no existing row, constraint, index or table is changed or deleted.
--   assignments.assigned_by_user_id   the account (Admin or Manager) that created the assignment. A plain identifier, no foreign key, so
--                                     deleting that account never touches an assignment. NULL (every existing row, and any assignment
--                                     made by the system) means "unknown": completion emails then go to the department Manager only.
--                                     A reassignment never changes it: the original assigner stays on record.
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after review:
--   psql -1 -v ON_ERROR_STOP=1 -f V26__assignment_assigned_by.sql
-- Deployment order: apply V26, then V27, then start the new backend. BOTH must be applied before the new backend starts, even while
-- task reminders are switched off: the production profile runs Hibernate schema validation and the assignment entity maps this column, so
-- a backend started without it fails at startup (and every assignment query would fail).
--
-- Rollback order: first roll the APPLICATION back to the previous version, and only then consider dropping the column. Dropping it while
-- the new backend is deployed breaks every assignment query. Loses only the "who assigned" record; assignments themselves are untouched.
--   ALTER TABLE assignments DROP COLUMN assigned_by_user_id;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'assignments' AND column_name = 'assigned_by_user_id') THEN
        RAISE EXCEPTION 'V26: assignments.assigned_by_user_id already exists. Review it by hand first. No changes were made.';
    END IF;
END
$$;

ALTER TABLE assignments ADD COLUMN assigned_by_user_id UUID;
