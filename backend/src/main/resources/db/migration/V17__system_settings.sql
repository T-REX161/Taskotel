-- V17: system-wide settings (Settings > System Settings, Admin only).
--
-- Adds one new table and nothing else: no existing table, row, column, constraint or index is read for change, updated or deleted.
-- The table holds at most ONE row. settings_key is always 'GLOBAL' (a CHECK) and is unique, so a second row cannot be added. No row is
-- added here: until an Admin saves the settings for the first time the application uses the defaults (auto-balancing on, capacity alert
-- on, cross-department assistance on, capacity threshold 85), so nothing is backfilled and every existing database keeps working as before.
--
-- updated_by_user_id has no foreign key on purpose: it is a record of who saved the settings, and it must survive any later change to
-- that account (the same choice as manager_department_changes in V15).
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after review:
--   psql -1 -v ON_ERROR_STOP=1 -f V17__system_settings.sql
--
-- Rollback (the application goes back to the defaults; nothing else is touched):  DROP TABLE system_settings;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.system_settings', current_schema())) IS NOT NULL THEN
        RAISE EXCEPTION 'V17: %.system_settings already exists. Review it by hand first. No changes were made.', current_schema();
    END IF;
END
$$;

CREATE TABLE system_settings (
    id                                  UUID         PRIMARY KEY,
    settings_key                        VARCHAR(20)  NOT NULL,
    auto_balancing_enabled              BOOLEAN      NOT NULL,
    capacity_alert_enabled              BOOLEAN      NOT NULL,
    cross_department_assistance_enabled BOOLEAN      NOT NULL,
    capacity_threshold_percent          INTEGER      NOT NULL,
    updated_at                          TIMESTAMP    NOT NULL,
    updated_by_user_id                  UUID         NOT NULL,
    updated_by_username                 VARCHAR(255) NOT NULL,
    CONSTRAINT uk_system_settings_key UNIQUE (settings_key),
    CONSTRAINT ck_system_settings_key CHECK (settings_key = 'GLOBAL'),
    CONSTRAINT ck_system_settings_threshold CHECK (capacity_threshold_percent BETWEEN 1 AND 100)
);

COMMENT ON TABLE system_settings IS 'Created by Flyway V17: the single row of system-wide settings';
