-- V25: Administrator-only system role changes.
--
-- Adds one column and one table, nothing else: no existing row, constraint or column is changed or deleted.
--   users.sessions_valid_after   nullable. Set when an Administrator changes the account's role: a session token issued before this moment is
--                                refused (the account signs in again). NULL (every existing row) means "never invalidated".
--   user_role_changes            append-only audit of role changes (plain identifiers with name snapshots, no foreign keys): who acted,
--                                who was changed, the previous and the new role, when, and how many Manager department grants ended.
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after review:
--   psql -1 -v ON_ERROR_STOP=1 -f V25__user_role_management.sql
-- The production profile only validates the schema, so apply this BEFORE starting a backend version that contains the role-change feature.
--
-- Rollback (removes the role history; role changes already made stay as they are):
--   DROP TABLE user_role_changes; ALTER TABLE users DROP COLUMN sessions_valid_after;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.user_role_changes', current_schema())) IS NOT NULL THEN
        RAISE EXCEPTION 'V25: %.user_role_changes already exists. Review it by hand first. No changes were made.', current_schema();
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'users' AND column_name = 'sessions_valid_after') THEN
        RAISE EXCEPTION 'V25: users.sessions_valid_after already exists. Review it by hand first. No changes were made.';
    END IF;
END
$$;

ALTER TABLE users ADD COLUMN sessions_valid_after TIMESTAMP;

CREATE TABLE user_role_changes (
    id                   UUID         PRIMARY KEY,
    target_user_id       UUID         NOT NULL,
    target_username      VARCHAR(255) NOT NULL,
    previous_role        VARCHAR(16)  NOT NULL,
    new_role             VARCHAR(16)  NOT NULL,
    revoked_grants       INTEGER      NOT NULL DEFAULT 0,
    changed_by_user_id   UUID         NOT NULL,
    changed_by_username  VARCHAR(255) NOT NULL,
    changed_at           TIMESTAMP    NOT NULL,
    CONSTRAINT ck_user_role_changes_roles CHECK (previous_role IN ('ADMIN', 'MANAGER', 'STAFF') AND new_role IN ('ADMIN', 'MANAGER', 'STAFF') AND previous_role <> new_role)
);

CREATE INDEX ix_user_role_changes_target ON user_role_changes (target_user_id, changed_at);
