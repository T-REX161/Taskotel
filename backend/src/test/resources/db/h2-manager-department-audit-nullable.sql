-- H2 stand-in for Flyway V20 (the Manager department audit can record a first assignment and a removal).
-- V20 itself uses PostgreSQL-only syntax (a DO block and SET LOCAL), so the production-profile tests (ddl-auto=validate against a schema
-- built from SQL files) load this instead. Same effect: the four department snapshot columns accept NULL.
ALTER TABLE manager_department_changes ALTER COLUMN previous_department_code DROP NOT NULL;
ALTER TABLE manager_department_changes ALTER COLUMN previous_department_name DROP NOT NULL;
ALTER TABLE manager_department_changes ALTER COLUMN new_department_code DROP NOT NULL;
ALTER TABLE manager_department_changes ALTER COLUMN new_department_name DROP NOT NULL;
