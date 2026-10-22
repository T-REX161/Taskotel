-- V2__add_credentials_setup_required.sql
-- Hotel Staff Workload Balancer: Add credentials_setup_required column for staff first-login onboarding

ALTER TABLE users ADD COLUMN IF NOT EXISTS credentials_setup_required BOOLEAN DEFAULT FALSE;

-- Backfill existing accounts based on role and verification status
UPDATE users SET credentials_setup_required = FALSE WHERE role IN ('ADMIN', 'MANAGER') AND credentials_setup_required IS NULL;
UPDATE users SET credentials_setup_required = FALSE WHERE role = 'STAFF' AND email_verified = TRUE AND credentials_setup_required IS NULL;
UPDATE users SET credentials_setup_required = TRUE WHERE role = 'STAFF' AND email_verified = FALSE AND credentials_setup_required IS NULL;

-- Enforce constraints
ALTER TABLE users ALTER COLUMN credentials_setup_required SET DEFAULT FALSE;
ALTER TABLE users ALTER COLUMN credentials_setup_required SET NOT NULL;
