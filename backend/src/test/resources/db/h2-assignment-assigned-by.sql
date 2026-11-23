-- H2 stand-in for Flyway V26 (who made the assignment). V26 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the
-- production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead. Same nullable column and type.
ALTER TABLE assignments ADD COLUMN IF NOT EXISTS assigned_by_user_id UUID;
