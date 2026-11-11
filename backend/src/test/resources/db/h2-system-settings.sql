-- H2 stand-in for the system_settings table that Flyway V17 creates in PostgreSQL.
-- V17 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the production-profile tests (ddl-auto=validate against a
-- schema built from SQL files) load this instead. Same columns and types.
CREATE TABLE IF NOT EXISTS system_settings (
    id UUID PRIMARY KEY,
    settings_key VARCHAR(20) NOT NULL,
    auto_balancing_enabled BOOLEAN NOT NULL,
    capacity_alert_enabled BOOLEAN NOT NULL,
    cross_department_assistance_enabled BOOLEAN NOT NULL,
    capacity_threshold_percent INTEGER NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by_user_id UUID NOT NULL,
    updated_by_username VARCHAR(255) NOT NULL,
    CONSTRAINT uk_system_settings_key UNIQUE (settings_key)
);
