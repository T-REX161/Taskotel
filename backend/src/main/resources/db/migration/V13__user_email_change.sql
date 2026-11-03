-- V13: secure email address change (Profile > Contact Information).
--
-- Five nullable columns on users, nothing else: no row is read for change, updated or deleted, and no constraint or index changes.
-- They hold at most ONE pending change per account, kept apart from the first-time verification columns and the password-reset
-- columns so none of those flows can touch another. The account's real email (users.email, still UNIQUE) is only replaced after the
-- new address has been proven with a code; a pending address is never used to sign in.
--   pending_email              the new address waiting to be verified (lower-case)
--   pending_email_code_hash    BCrypt hash of the six-digit code (the code itself is never stored)
--   pending_email_expires_at   when the code stops working
--   pending_email_attempt_count wrong guesses so far (the code is cancelled after five)
--   pending_email_last_sent_at when the last code was sent (resend cooldown)
-- NULL everywhere means "no change in progress", which is how every existing account already is, so nothing is backfilled.
--
-- Run by hand in one transaction:  psql -1 -v ON_ERROR_STOP=1 -f V13__user_email_change.sql
--
-- Rollback (a change in progress is simply forgotten; nobody's email is affected):
--   ALTER TABLE users DROP COLUMN pending_email_last_sent_at;
--   ALTER TABLE users DROP COLUMN pending_email_attempt_count;
--   ALTER TABLE users DROP COLUMN pending_email_expires_at;
--   ALTER TABLE users DROP COLUMN pending_email_code_hash;
--   ALTER TABLE users DROP COLUMN pending_email;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.users', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V13: %.users does not exist. Apply V1 first. No changes were made.', current_schema();
    END IF;
END
$$;

ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email_code_hash VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email_expires_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email_attempt_count INTEGER;
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email_last_sent_at TIMESTAMP;
