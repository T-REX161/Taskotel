-- H2 stand-in for the support_assignments table that Flyway V5 creates in PostgreSQL.
-- V5 itself uses PostgreSQL-only syntax (a DO block and a partial unique index), so the production-profile tests
-- (ddl-auto=validate against a schema built from SQL files) load this instead. Same columns and types.
CREATE TABLE IF NOT EXISTS support_assignments (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    staff_id UUID NOT NULL,
    staff_name VARCHAR(255) NOT NULL,
    home_department_code VARCHAR(64) NOT NULL,
    receiving_department_code VARCHAR(64) NOT NULL,
    task_id UUID NOT NULL,
    task_title VARCHAR(255) NOT NULL,
    assignment_id UUID,
    approved_by_user_id UUID,
    approved_by_username VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    end_reason VARCHAR(30),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    archived_at TIMESTAMP,
    archived_by_username VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0
);
-- V6 replaces UNIQUE (task_id) with a PostgreSQL-only partial unique index (one ACTIVE record per task); H2 has no
-- equivalent, so the service's row locks are what these tests exercise.
