-- H2 stand-in for Flyway V13 (email change). V13 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the
-- production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead. Same columns and types.
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email_code_hash VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email_expires_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email_attempt_count INTEGER;
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email_last_sent_at TIMESTAMP;
