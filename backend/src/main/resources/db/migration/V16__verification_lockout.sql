-- V16: persisted lockout and resend cooldown for email verification (Step 2J, stage 2).
--
-- Adds two nullable columns to users and nothing else: no row is read for change, updated or deleted, no constraint or index changes,
-- and every existing row stays valid (NULL means "not locked" and "never sent", exactly the behaviour before).
--   verification_locked_until  when set and in the future, verification attempts are refused until then (15 minutes after the fifth
--                              cumulative failed attempt)
--   verification_last_sent_at  when a verification email was last attempted for this account; sends within 60 seconds of it are
--                              suppressed
-- The failed-attempt counter keeps using the existing verification_attempt_count column; only its meaning changes in the code (it is no
-- longer reset by a resend).
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after review:
--   psql -1 -v ON_ERROR_STOP=1 -f V16__verification_lockout.sql
--
-- Rollback (loses only the lockout and cooldown marks; no verification code or account is touched):
--   ALTER TABLE users DROP COLUMN verification_last_sent_at;
--   ALTER TABLE users DROP COLUMN verification_locked_until;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.users', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V16: %.users does not exist. Apply V1 first. No changes were made.', current_schema();
    END IF;
END
$$;

ALTER TABLE users ADD COLUMN IF NOT EXISTS verification_locked_until TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS verification_last_sent_at TIMESTAMP;
