-- H2 stand-in for Flyway V16 (verification lockout and resend cooldown). V16 itself uses PostgreSQL-only syntax (a DO block and
-- SET LOCAL), so the production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead.
-- Same columns and types.
ALTER TABLE users ADD COLUMN IF NOT EXISTS verification_locked_until TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS verification_last_sent_at TIMESTAMP;
