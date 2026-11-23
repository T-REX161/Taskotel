-- H2 stand-in for Flyway V27 (task reminder log). V27 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the
-- production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead. Same table, types and constraints.
CREATE TABLE IF NOT EXISTS task_reminder_log (
    id                 UUID        PRIMARY KEY,
    task_id            UUID        NOT NULL,
    recipient_user_id  UUID        NOT NULL,
    kind               VARCHAR(16) NOT NULL,
    slot               INTEGER     NOT NULL,
    due_key            VARCHAR(32) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    claimed_at         TIMESTAMP   NOT NULL,
    finished_at        TIMESTAMP,
    CONSTRAINT uk_task_reminder_log_key UNIQUE (task_id, recipient_user_id, kind, slot, due_key),
    CONSTRAINT fk_task_reminder_log_task FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE,
    CONSTRAINT ck_task_reminder_log_kind CHECK (kind IN ('DUE_24H', 'DUE_2H', 'DUE_TODAY', 'OVERDUE')),
    CONSTRAINT ck_task_reminder_log_status CHECK (status IN ('CLAIMED', 'SENT', 'FAILED', 'SKIPPED', 'EXPIRED', 'UNKNOWN'))
);
