-- H2 stand-in for Flyway V14 (user phone). V14 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the
-- production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead. Same column and type.
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(50);
