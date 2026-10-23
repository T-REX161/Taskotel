-- H2 stand-in for the departments table that Flyway V4 creates in PostgreSQL.
-- V4 itself uses PostgreSQL-only syntax, so the production-profile tests (ddl-auto=validate against a
-- schema built from SQL files) load this instead. Same columns and types; no seed rows or foreign keys needed to start.
CREATE TABLE IF NOT EXISTS departments (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    icon_key VARCHAR(50) NOT NULL DEFAULT 'building',
    color_key VARCHAR(50) NOT NULL DEFAULT 'gray',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    head_staff_id UUID,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uk_departments_code UNIQUE (code)
);
