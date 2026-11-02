-- V12: notification preferences (Settings > Notification Preferences): per user, per category, in-app and email.
--
-- Adds one new table and nothing else: no existing table, row, constraint or column is read for change, updated or deleted.
-- A row exists only once a user has saved a choice for a category; until then the application uses the category's default
-- (in-app on for all; email on for task assignment, workload alerts and security alerts), so every existing and new account has a
-- complete set without any row being created or any data backfilled. Security alerts are mandatory: the application never
-- stores or reports them as off.
--
-- user_id references users(id) and the rows are removed with their user (ON DELETE CASCADE); deleting a preference never touches a user.
--
-- Run by hand in one transaction:  psql -1 -v ON_ERROR_STOP=1 -f V12__notification_preferences.sql
--
-- Rollback (everyone goes back to the defaults):  DROP TABLE notification_preferences;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.users', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V12: %.users does not exist. Apply V1 first. No changes were made.', current_schema();
    END IF;
    IF to_regclass(format('%I.notification_preferences', current_schema())) IS NOT NULL THEN
        RAISE EXCEPTION 'V12: %.notification_preferences already exists. Review it by hand first. No changes were made.', current_schema();
    END IF;
END
$$;

CREATE TABLE notification_preferences (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    category   VARCHAR(30) NOT NULL,
    in_app     BOOLEAN     NOT NULL,
    email      BOOLEAN     NOT NULL,
    updated_at TIMESTAMP   NOT NULL,
    CONSTRAINT uk_notification_preferences_user_category UNIQUE (user_id, category),
    CONSTRAINT ck_notification_preferences_category CHECK (category IN
        ('TASK_ASSIGNED', 'TASK_STATUS', 'WORKLOAD_ALERT', 'TASK_REMINDER', 'SECURITY'))
);

COMMENT ON TABLE notification_preferences IS 'Created by Flyway V12: per-user notification preferences';
