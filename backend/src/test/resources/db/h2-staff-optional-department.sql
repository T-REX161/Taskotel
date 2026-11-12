-- H2 stand-in for Flyway V19 (a STAFF account may have no department yet).
-- V19 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the production-profile tests (ddl-auto=validate against a schema
-- built from SQL files) load this instead. Same effect: staff.department accepts NULL.
ALTER TABLE staff ALTER COLUMN department DROP NOT NULL;
