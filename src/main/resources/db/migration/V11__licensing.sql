-- Milestone M14 (licensing, activation and remote kill switch): a single-row table holding
-- this installation's licence state, mirroring shop_profile's CHECK (id = 1) singleton shape
-- (see V1__initial_schema.sql) since exactly one licence applies to one installed copy.
--
-- lease_token/lease_expires_at hold the most recently issued signed lease (see
-- LicenseVerifier) - stored unencrypted, deliberately: the lease is tamper-evident by its
-- Ed25519 signature already, and DPAPI would only add a failure mode (a Windows profile
-- change would destroy an otherwise perfectly valid lease). Nothing in this table is a
-- secret; the value that must never be readable is the signing PRIVATE key, which never
-- exists on this machine at all.
--
-- clock_watermark is the highest instant this installation has ever observed, used to detect
-- a large system-clock rollback attempting to keep an expired lease looking current.
--
-- No CHECK constraint on fingerprint/lease columns: a fresh install activates before any of
-- this is known, so the row is written incrementally as activation proceeds.
CREATE TABLE license (
    id                   INTEGER PRIMARY KEY CHECK (id = 1),
    license_id           TEXT,
    activation_key       TEXT NOT NULL,
    shop_name            TEXT,
    fingerprint          TEXT NOT NULL,
    volume_serial        TEXT,
    lease_token          TEXT,
    lease_expires_at     TEXT,
    last_contact_at      TEXT,
    last_contact_result  TEXT,
    clock_watermark      TEXT NOT NULL,
    activated_at         TEXT NOT NULL
);
