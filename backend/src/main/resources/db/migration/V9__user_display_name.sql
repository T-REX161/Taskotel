-- V9: editable display name. Each account can choose the name shown in the app (profile banner and top navigation).
--
-- One nullable column on users, nothing else: no row is read for change, updated or deleted, and no constraint or index changes.
-- NULL means "not chosen yet": the application then shows the username with a capital first letter, so every existing account
-- keeps working and already has a sensible name without any data being rewritten.
--   display_name  the chosen name (at most 50 characters, validated by the application)
--
-- Run by hand in one transaction:  psql -1 -v ON_ERROR_STOP=1 -f V9__user_display_name.sql
--
-- Rollback (forgets the chosen names; everyone goes back to the default):
--   ALTER TABLE users DROP COLUMN display_name;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.users', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V9: %.users does not exist. Apply V1 first. No changes were made.', current_schema();
    END IF;
END
$$;

ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name VARCHAR(50);
