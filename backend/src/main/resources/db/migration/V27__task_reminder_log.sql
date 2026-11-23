-- V27: the log that keeps Task Reminder emails from repeating (see docs/TASK_REMINDERS.md).
--
-- Adds one new table and nothing else: no existing table, row, constraint or column is changed or deleted.
--   task_reminder_log   one row per (task, recipient, reminder kind, slot, due moment). The row is inserted BEFORE the email is handed to
--                       the notification worker (status CLAIMED); the unique key makes a second scheduler run, or a second application
--                       instance, unable to claim the same reminder. The worker then records SENT (SendGrid accepted the request), FAILED
--                       (SendGrid CONFIRMED a refusal), UNKNOWN (a broken connection: it may have been accepted), SKIPPED (the recipient could
--                       not or did not want it) or EXPIRED (the reminder's window closed before the worker reached it). CLAIMED, SENT and
--                       UNKNOWN count toward the cap of three overdue emails. A reminder the queue rejects has its claim removed again
--                       so the next run may try. due_key is the task's due date (and deadline time)
--                       as text, so changing a task's due date starts a fresh set of reminders. The foreign key cascades: deleting a
--                       task removes its reminder rows. recipient_user_id is a plain identifier (no foreign key).
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after review and a rehearsal on a
-- disposable database:
--   psql -1 -v ON_ERROR_STOP=1 -f V27__task_reminder_log.sql
-- Deployment order: apply V26, then V27, then start the new backend. BOTH must be applied before the new backend starts, even while
-- reminders are switched off (taskotel.reminders.enabled=false): the production profile runs Hibernate schema validation, and the reminder
-- log is mapped whether or not the feature is on, so a backend started without this table fails at startup.
--
-- Rollback order: first roll the APPLICATION back to the previous version, and only then consider reverting the migration. Dropping the
-- table while the new backend is still deployed makes it fail its schema validation on the next start. Reminders already sent stay sent;
-- a re-enabled feature would start from a clean log (and so from a clean overdue count: a task could be reminded again).
--   DROP TABLE task_reminder_log;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.tasks', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V27: %.tasks does not exist. No changes were made.', current_schema();
    END IF;
    IF to_regclass(format('%I.task_reminder_log', current_schema())) IS NOT NULL THEN
        RAISE EXCEPTION 'V27: %.task_reminder_log already exists. Review it by hand first. No changes were made.', current_schema();
    END IF;
END
$$;

CREATE TABLE task_reminder_log (
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
