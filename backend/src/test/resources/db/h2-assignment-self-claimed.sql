-- H2 stand-in for Flyway V29 (assignments the Staff member accepted themselves). V29 itself uses PostgreSQL-only syntax (a DO block and
-- SET LOCAL), so the production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead. Same column,
-- type and default.
ALTER TABLE assignments ADD COLUMN IF NOT EXISTS self_claimed BOOLEAN NOT NULL DEFAULT FALSE;
