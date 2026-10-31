-- V8: profile pictures. Each account can point at one image stored in Cloudinary.
--
-- Two nullable columns on users, nothing else: no row is read for change, updated or deleted, no constraint or index changes,
-- and every existing account stays valid (NULL means "no picture"; the default avatar is shown).
--   profile_image_url        the HTTPS delivery URL of the picture
--   profile_image_public_id  the Cloudinary public id, needed to delete the picture when it is replaced or removed
--
-- Run by hand in one transaction:  psql -1 -v ON_ERROR_STOP=1 -f V8__user_profile_image.sql
--
-- Rollback (forgets which picture belongs to which account; the images stay in Cloudinary until deleted there):
--   ALTER TABLE users DROP COLUMN profile_image_public_id;
--   ALTER TABLE users DROP COLUMN profile_image_url;

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass(format('%I.users', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'V8: %.users does not exist. Apply V1 first. No changes were made.', current_schema();
    END IF;
END
$$;

ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_url VARCHAR(512);
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_public_id VARCHAR(255);
