-- H2 stand-in for Flyway V8 (profile pictures). V8 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the
-- production-profile tests (ddl-auto=validate against a schema built from SQL files) load this instead. Same columns and types.
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_url VARCHAR(512);
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_public_id VARCHAR(255);
