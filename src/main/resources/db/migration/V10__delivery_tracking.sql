-- Milestone M13 (Bookings tab): records whether each sold piece has physically reached the
-- customer. One nullable column rather than a new table because a sales_line is exactly one
-- piece (FR-SAL-01), so a per-line stamp is already a per-piece stamp.
--
-- NULL means "not yet delivered". No CHECK constraint and no backfill: every pre-M13 line is
-- genuinely of unknown delivery state, and NULL says exactly that. Additive-only, following
-- the V9 precedent, because SQLite cannot drop a constraint later without rebuilding the
-- table.
ALTER TABLE sales_line ADD COLUMN delivered_at TEXT;
