-- V5: temporary cross-department support assignments.
--
-- Adds one new table and nothing else: no existing table, row, constraint or column is read for change, updated or
-- deleted. A staff member's permanent department (staff.department) is never touched by this feature; the table only
-- records, per supported task, who helped, from which home department, for which receiving department, who approved
-- it, and when it started and ended.
--
-- Staff, task, departments and the approving user are plain identifiers with name/code snapshots (no foreign keys), so
-- the history stays valid after a task or staff record is deleted.
--
-- Rollback: DROP TABLE support_assignments;  (it holds only support history, nothing else depends on it).

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.support_assignments', current_schema())) IS NOT NULL THEN
        RAISE EXCEPTION 'V5: %.support_assignments already exists. Review it by hand first. No changes were made.', current_schema();
    END IF;
END
$$;

CREATE TABLE support_assignments (
    id                        UUID         PRIMARY KEY,
    plan_id                   UUID         NOT NULL,
    staff_id                  UUID         NOT NULL,
    staff_name                VARCHAR(255) NOT NULL,
    home_department_code      VARCHAR(64)  NOT NULL,
    receiving_department_code VARCHAR(64)  NOT NULL,
    task_id                   UUID         NOT NULL,
    task_title                VARCHAR(255) NOT NULL,
    assignment_id             UUID,
    approved_by_user_id       UUID,
    approved_by_username      VARCHAR(255) NOT NULL,
    status                    VARCHAR(20)  NOT NULL,
    end_reason                VARCHAR(30),
    created_at                TIMESTAMP    NOT NULL,
    completed_at              TIMESTAMP,
    version                   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_support_assignments_task UNIQUE (task_id),
    CONSTRAINT ck_support_assignments_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_support_assignments_end_reason CHECK (end_reason IS NULL OR end_reason IN
        ('TASK_COMPLETED', 'TASK_CANCELLED', 'TASK_DELETED', 'TASK_REASSIGNED', 'TASK_MOVED'))
);

CREATE INDEX idx_support_assignments_staff_status ON support_assignments (staff_id, status);
CREATE INDEX idx_support_assignments_plan ON support_assignments (plan_id);

-- Database-level guarantee that one person has at most one active support assignment
CREATE UNIQUE INDEX uk_support_assignments_active_staff ON support_assignments (staff_id) WHERE status = 'ACTIVE';

COMMENT ON TABLE support_assignments IS 'Created by Flyway V5: temporary cross-department support assignments';
