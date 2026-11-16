-- H2 stand-in for Flyway V24 (workload alert state). V24 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the
-- production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead.
-- Same table, columns, types and constraints.
CREATE TABLE IF NOT EXISTS workload_alert_state (
    id          UUID        PRIMARY KEY,
    staff_id    UUID        NOT NULL,
    audience    VARCHAR(8)  NOT NULL,
    level       VARCHAR(16) NOT NULL,
    changed_at  TIMESTAMP   NOT NULL,
    CONSTRAINT uk_workload_alert_state_staff_audience UNIQUE (staff_id, audience),
    CONSTRAINT fk_workload_alert_state_staff FOREIGN KEY (staff_id) REFERENCES staff (id) ON DELETE CASCADE,
    CONSTRAINT ck_workload_alert_state_audience CHECK (audience IN ('STAFF', 'MANAGER')),
    CONSTRAINT ck_workload_alert_state_level CHECK (level IN ('LOW', 'NORMAL', 'HIGH', 'AT_RISK'))
);
