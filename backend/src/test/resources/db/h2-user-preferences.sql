-- H2 stand-in for Flyway V11 (personal preferences). V11 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the
-- production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead. Same columns and types.
ALTER TABLE users ADD COLUMN IF NOT EXISTS theme_preference VARCHAR(10);
ALTER TABLE users ADD COLUMN IF NOT EXISTS date_format VARCHAR(10);
ALTER TABLE users ADD COLUMN IF NOT EXISTS time_format VARCHAR(10);
