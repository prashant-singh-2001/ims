-- Milestone M12 (OneDrive as an alternative backup destination): backup_history needs to
-- record which cloud provider an archive actually went to, since FR-BAK-07/08 now cover two
-- (Google Drive and OneDrive) and an owner can switch which one is active after archives
-- already exist under the other.
--
-- drive_file_id is renamed to remote_file_id because it is no longer Drive-specific - it
-- holds a Google Drive file id or a Microsoft Graph item id depending on provider. SQLite
-- 3.25+ supports RENAME COLUMN directly (this project runs 3.53), so no table rebuild is
-- needed for the rename.
--
-- provider has no CHECK constraint (unlike backup_type/status above it) deliberately: SQLite
-- cannot drop a CHECK constraint without rebuilding the table, and a third provider one day
-- should not require that. NULL means "no remote copy exists for this row", matching how
-- remote_file_id itself already works - the backfill below only sets it where a remote copy
-- is actually recorded.
ALTER TABLE backup_history RENAME COLUMN drive_file_id TO remote_file_id;
ALTER TABLE backup_history ADD COLUMN provider TEXT;

UPDATE backup_history SET provider = 'GOOGLE_DRIVE' WHERE remote_file_id IS NOT NULL;
