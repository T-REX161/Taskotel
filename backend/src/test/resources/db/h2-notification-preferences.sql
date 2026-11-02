-- H2 stand-in for the notification_preferences table that Flyway V12 creates in PostgreSQL.
-- V12 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the production-profile tests (ddl-auto=validate against a
-- schema built from SQL files) load this instead. Same columns and types.
CREATE TABLE IF NOT EXISTS notification_preferences (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    category VARCHAR(30) NOT NULL,
    in_app BOOLEAN NOT NULL,
    email BOOLEAN NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_notification_preferences_user_category UNIQUE (user_id, category)
);
