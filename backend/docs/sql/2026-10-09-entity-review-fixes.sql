-- Hand-run schema changes for the 2026-10-09 entity review fixes (MySQL, schema `leave`).
--
-- Why by hand: hibernate.hbm2ddl.auto=update only ADDS tables/columns/indexes. It never
-- renames a table, changes an enum(...) value list, tightens NULL -> NOT NULL, drops a
-- column, or backfills data (see backend/CLAUDE.md, Configuration). No Flyway by design,
-- so this script is the migration.
--
-- How: take a backup, STOP the app, run PART A, start the new build once (Hibernate
-- creates employee_photo and the new indexes), then run PART B. Every statement is
-- written for a database that is on the previous build; read each one before running.

-- =====================================================================================
-- PART A -- run BEFORE the first start of the new build
-- =====================================================================================

-- #2 Auth table renamed `user` -> account. If skipped, Hibernate creates an empty
-- `account` table and every existing login breaks.
RENAME TABLE `user` TO account;
ALTER TABLE account RENAME INDEX uk_user_org_email TO uk_account_org_email;
-- #1 email 190 -> 189 so (org_id, email) fits MySQL's 767-byte key limit. Fails if any
-- stored email is 190 chars long -- check first: SELECT id FROM account WHERE CHAR_LENGTH(email) > 189;
ALTER TABLE account MODIFY email VARCHAR(189) NOT NULL;

-- #3 Status split: employment state moves to employee.status; account.status keeps only
-- INVITED / PENDING / ACTIVE / DISABLED. Mapping from the old single status:
--   INVITED   -> account INVITED,  employee ONBOARDING
--   PENDING   -> account PENDING,  employee ONBOARDING
--   ONBOARDED -> account ACTIVE,   employee ONBOARDED
--   ACTIVE    -> account ACTIVE,   employee ACTIVE
--   ON_NOTICE -> account ACTIVE,   employee ON_NOTICE
--   EXITED    -> account DISABLED, employee EXITED
ALTER TABLE employee ADD COLUMN status VARCHAR(16) NULL;
UPDATE employee e JOIN account a ON a.id = e.user_id
   SET e.status = CASE a.status
       WHEN 'INVITED'   THEN 'ONBOARDING'
       WHEN 'PENDING'   THEN 'ONBOARDING'
       WHEN 'ONBOARDED' THEN 'ONBOARDED'
       WHEN 'ON_NOTICE' THEN 'ON_NOTICE'
       WHEN 'EXITED'    THEN 'EXITED'
       ELSE 'ACTIVE' END;
ALTER TABLE employee MODIFY status ENUM('ONBOARDING','ONBOARDED','ACTIVE','ON_NOTICE','EXITED') NOT NULL;
ALTER TABLE account MODIFY status VARCHAR(32) NOT NULL;
UPDATE account SET status = CASE status
       WHEN 'EXITED'    THEN 'DISABLED'
       WHEN 'ONBOARDED' THEN 'ACTIVE'
       WHEN 'ON_NOTICE' THEN 'ACTIVE'
       ELSE status END;
ALTER TABLE account MODIFY status ENUM('INVITED','PENDING','ACTIVE','DISABLED') NOT NULL;

-- #14 updated_at on three tables that change after insert. Added by hand because a NOT
-- NULL datetime with no default can't be added to a populated table in strict mode.
-- Skip a table's three lines if it already has updated_at (it did under an older
-- CreatedOnlyPojo/AuditedPojo mapping): SHOW COLUMNS FROM organization LIKE 'updated_at';
ALTER TABLE organization ADD COLUMN updated_at DATETIME(6) NULL;
UPDATE organization SET updated_at = created_at;
ALTER TABLE organization MODIFY updated_at DATETIME(6) NOT NULL;
ALTER TABLE notification ADD COLUMN updated_at DATETIME(6) NULL;
UPDATE notification SET updated_at = created_at;
ALTER TABLE notification MODIFY updated_at DATETIME(6) NOT NULL;
ALTER TABLE email_outbox ADD COLUMN updated_at DATETIME(6) NULL;
UPDATE email_outbox SET updated_at = COALESCE(sent_at, created_at);
ALTER TABLE email_outbox MODIFY updated_at DATETIME(6) NOT NULL;

-- Warning: holiday_year dropped (it was NOT NULL with no default, so inserts fail once
-- the entity stops writing it). Year queries now use the holiday_date range.
ALTER TABLE public_holiday DROP COLUMN holiday_year;

-- #17 Index renames (renaming in place, so Hibernate doesn't add a duplicate).-
ALTER TABLE employee           RENAME INDEX ix_emp_mgr              TO idx_employee_manager;
ALTER TABLE leave_ledger_entry RENAME INDEX ix_lledg_user_type_year TO idx_leave_ledger_entry_user_type_year;
ALTER TABLE leave_request      RENAME INDEX ix_lreq_user            TO idx_leave_request_user;
ALTER TABLE leave_request      RENAME INDEX ix_lreq_leave_type      TO idx_leave_request_leave_type;

-- #4 employee_code / date_of_joining now nullable (no more placeholder values).
ALTER TABLE employee MODIFY employee_code VARCHAR(64) NULL, MODIFY date_of_joining DATE NULL;
-- Optional clean-up of the old auto-generated placeholder codes. REVIEW FIRST: an HR-typed
-- code could also start with EMP-. Placeholder joining dates (= the creation day) can't bej
-- told apart from real ones automatically; fix those by hand from HR's records.
-- SELECT user_id, employee_code FROM employee WHERE employee_code REGEXP '^(ADMIN|EMP)-[0-9a-f]{8}$';
-- UPDATE employee SET employee_code = NULL WHERE employee_code REGEXP '^(ADMIN|EMP)-[0-9a-f]{8}$';

-- #10 applicable_employee_type: NULL now means "everyone" (was the string 'ALL').
ALTER TABLE leave_type MODIFY applicable_employee_type VARCHAR(32) NULL;
UPDATE leave_type SET applicable_employee_type = NULL WHERE UPPER(applicable_employee_type) = 'ALL';

-- Warning: approver_role now NOT NULL; NULL used to mean MANAGER.
UPDATE leave_type SET approver_role = 'MANAGER' WHERE approver_role IS NULL;
ALTER TABLE leave_type MODIFY approver_role ENUM('MANAGER','HR') NOT NULL;

-- Warning: organization.country now NOT NULL. Set real values for any NULL rows first:
-- SELECT id, org_key FROM organization WHERE country IS NULL;
ALTER TABLE organization MODIFY country VARCHAR(2) NOT NULL;

-- #11 blood_group stored as the enum constant name, not the "A+" label.
ALTER TABLE employee_sensitive MODIFY blood_group VARCHAR(8) NULL;
UPDATE employee_sensitive SET blood_group = CASE blood_group
       WHEN 'A+' THEN 'A_POS'  WHEN 'A-' THEN 'A_NEG'
       WHEN 'B+' THEN 'B_POS'  WHEN 'B-' THEN 'B_NEG'
       WHEN 'AB+' THEN 'AB_POS' WHEN 'AB-' THEN 'AB_NEG'
       WHEN 'O+' THEN 'O_POS'  WHEN 'O-' THEN 'O_NEG'
       ELSE NULL END
 WHERE blood_group IS NOT NULL;
ALTER TABLE employee_sensitive MODIFY blood_group ENUM('A_POS','A_NEG','B_POS','B_NEG','AB_POS','AB_NEG','O_POS','O_NEG') NULL;

-- Warning: bank/ID columns sized to what FieldRules has always enforced.
ALTER TABLE employee_sensitive
  MODIFY bank_account VARCHAR(34) NULL,
  MODIFY national_id  VARCHAR(30) NULL,
  MODIFY tax_id       VARCHAR(30) NULL,
  MODIFY passport     VARCHAR(30) NULL;

-- #12 / #13 plan.code and audit_log.action are enums now (values already match).
ALTER TABLE plan MODIFY code ENUM('FREE','STARTER','GROWTH','ENTERPRISE') NOT NULL;
ALTER TABLE audit_log MODIFY action ENUM('EMPLOYEE_DETAILS_UPDATED') NOT NULL;

-- =====================================================================================
-- PART B -- run AFTER the new build has started once (it creates employee_photo)
-- =====================================================================================

-- #15 Photo moved to its own table.
INSERT INTO employee_photo (user_id, bytes, content_type, created_at, updated_at)
SELECT user_id, photo, photo_content_type, updated_at, updated_at
  FROM employee
 WHERE photo IS NOT NULL;
ALTER TABLE employee DROP COLUMN photo, DROP COLUMN photo_content_type;
