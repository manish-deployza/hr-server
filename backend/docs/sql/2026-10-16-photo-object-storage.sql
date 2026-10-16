-- Profile photos move to the media bucket (MediaStorageService). Hibernate's `update` adds
-- employee_photo.storage_key by itself but cannot relax NOT NULL, so run this once on every
-- existing database BEFORE deploying the build that stores photos in the bucket.
-- Photos already in the table keep working: storage_key stays NULL and the bytes are read
-- from the row until the user uploads a new photo (which then goes to the bucket).
ALTER TABLE employee_photo MODIFY COLUMN bytes MEDIUMBLOB NULL;
