-- V14: phone number on the account (Profile > Contact Information).
--
-- One nullable column on users. Until now a phone number existed only on the staff record (staff.phone), so an Admin or Manager
-- without a staff record had nowhere to keep one. The profile now saves the number here and, when the account has a staff record,
-- keeps staff.phone in step. No row is read for change, updated or deleted, nothing is backfilled and no constraint or index changes.
-- NULL means "no phone number added", which is how every existing account already is.
-- The number is NOT verified (there is no SMS verification); it is only the number the user typed.
--
-- Run by hand in one transaction:  psql -1 -v ON_ERROR_STOP=1 -f V14__user_phone.sql
--
-- Rollback (the numbers saved here are forgotten; staff.phone is untouched):
--   ALTER TABLE users DROP COLUMN phone;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.users', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V14: %.users does not exist. Apply V1 first. No changes were made.', current_schema();
    END IF;
END
$$;

ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(50);
