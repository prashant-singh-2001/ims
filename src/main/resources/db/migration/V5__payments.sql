-- Milestone M5 (Money): customer receipts and supplier payments.
-- Scope matches docs/04-roadmap.md M5 and docs/02-data-model.md section 4.6.
--
-- This is the migration every "amount paid" comment left in V3/V4 was waiting for -
-- PurchaseBillService.balance, SupplierService.dues and SalesInvoiceService.balance all
-- get retrofitted to use these tables instead of hardcoding paid = zero.

-- Soft-deletable (FR-PAY-06): a wrongly entered payment is never hard-deleted, only
-- flagged and excluded from every balance calculation - the record and the reason stay.
CREATE TABLE payment (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    direction            TEXT NOT NULL CHECK (direction IN ('IN', 'OUT')),
    party_type           TEXT NOT NULL CHECK (party_type IN ('CUSTOMER', 'SUPPLIER')),
    party_id             INTEGER NOT NULL,
    payment_date         TEXT NOT NULL,
    amount               INTEGER NOT NULL CHECK (amount > 0),
    mode                 TEXT NOT NULL CHECK (mode IN ('CASH', 'UPI', 'CARD', 'BANK_TRANSFER', 'CHEQUE')),
    reference_no         TEXT,
    note                 TEXT,
    is_deleted           INTEGER NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),
    deleted_reason        TEXT,
    deleted_at            TEXT,
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

CREATE INDEX idx_payment_party ON payment(party_type, party_id);
CREATE INDEX idx_payment_date ON payment(payment_date);

-- A separate allocation table (rather than a single target column on payment itself)
-- costs nothing now and is the difference between "customer paid Rs.20,000 across three
-- invoices in one receipt" being expressible or not (docs/02-data-model.md section 4.6).
CREATE TABLE payment_allocation (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    payment_id           INTEGER NOT NULL REFERENCES payment(id),
    target_type          TEXT NOT NULL CHECK (target_type IN ('SALES_INVOICE', 'PURCHASE_BILL')),
    target_id            INTEGER NOT NULL,
    amount               INTEGER NOT NULL CHECK (amount > 0)
);

CREATE INDEX idx_payment_allocation_payment ON payment_allocation(payment_id);
CREATE INDEX idx_payment_allocation_target ON payment_allocation(target_type, target_id);
