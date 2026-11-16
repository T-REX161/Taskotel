-- V24: remembers the last workload level per Staff member and audience, so a workload alert email is sent only when the level goes UP into
-- HIGH or AT_RISK and never repeats while it stays there.
--
-- Adds one new table and nothing else: no existing table, row, constraint or column is changed or deleted.
--   workload_alert_state   one row per (Staff member, audience). Audience STAFF is the person's global workload, MANAGER is only the work of
--                          their home department (the two levels can differ). Rows are created lazily by the application, with no backfill.
--                          The unique constraint (staff_id, audience) makes creation safe when two workers race: the loser's insert is
--                          rejected and ignored. The foreign key to staff cascades, so a row never outlives its Staff record.
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after review and after a rehearsal
-- on a disposable database:
--   psql -1 -v ON_ERROR_STOP=1 -f V24__workload_alert_state.sql
--
-- Rollback (removes only this table; no workload, task or assignment data is touched, and alerts simply start from a fresh baseline):
--   DROP TABLE workload_alert_state;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.staff', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V24: %.staff does not exist. No changes were made.', current_schema();
    END IF;
    IF to_regclass(format('%I.workload_alert_state', current_schema())) IS NOT NULL THEN
        RAISE EXCEPTION 'V24: %.workload_alert_state already exists. Review it by hand first. No changes were made.', current_schema();
    END IF;
END
$$;

CREATE TABLE workload_alert_state (
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
