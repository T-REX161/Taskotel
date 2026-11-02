-- V10: account activity shown on the profile page ("Last Login" and "Last Password Change").
--
-- Two nullable columns on users, nothing else: no row is read for change, updated or deleted, and no constraint or index changes.
-- NULL means "not recorded". Nothing is backfilled: the password hash tells us nothing about when it was set, and updated_at changes
-- on any edit, so an old account simply shows "Not recorded yet" until it next signs in / changes its password.
--   last_login_at        set by the application on every successful sign-in
--   password_changed_at  set when the user sets or resets their own password
-- (Account Created already exists as users.created_at.)
--
-- Run by hand in one transaction:  psql -1 -v ON_ERROR_STOP=1 -f V10__user_account_activity.sql
--
-- Rollback (forgets the recorded times):
--   ALTER TABLE users DROP COLUMN password_changed_at;
--   ALTER TABLE users DROP COLUMN last_login_at;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.users', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V10: %.users does not exist. Apply V1 first. No changes were made.', current_schema();
    END IF;
END
$$;

ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP;
