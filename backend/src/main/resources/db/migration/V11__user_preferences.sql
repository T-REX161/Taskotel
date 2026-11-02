-- V11: personal display preferences (Settings > Personal Preferences): appearance, date format and time format.
--
-- Three nullable columns on users, nothing else: no row is read for change, updated or deleted, and no constraint or index changes.
-- Preferences belong to the user's own row (never global). NULL means "not chosen yet": the application then uses the defaults
-- (theme LIGHT, date DMY, time H12), which is how every existing account already behaves, so nothing is rewritten or backfilled.
--   theme_preference  LIGHT | DARK | SYSTEM
--   date_format       DMY (DD/MM/YYYY) | MDY (MM/DD/YYYY) | YMD (YYYY-MM-DD)
--   time_format       H12 (12-hour) | H24 (24-hour)
-- The allowed values are enforced by the application, in the same way as the other user fields.
--
-- Run by hand in one transaction:  psql -1 -v ON_ERROR_STOP=1 -f V11__user_preferences.sql
--
-- Rollback (everyone goes back to the defaults):
--   ALTER TABLE users DROP COLUMN time_format;
--   ALTER TABLE users DROP COLUMN date_format;
--   ALTER TABLE users DROP COLUMN theme_preference;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.users', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V11: %.users does not exist. Apply V1 first. No changes were made.', current_schema();
    END IF;
END
$$;

ALTER TABLE users ADD COLUMN IF NOT EXISTS theme_preference VARCHAR(10);
ALTER TABLE users ADD COLUMN IF NOT EXISTS date_format VARCHAR(10);
ALTER TABLE users ADD COLUMN IF NOT EXISTS time_format VARCHAR(10);
