-- Milestone M3 (Purchases): suppliers, purchase bills and their lines, purchase returns.
-- Scope matches docs/04-roadmap.md M3 and docs/02-data-model.md section 4.4.
--
-- "Amount paid" per bill (FR-PUR-09) is computed from the payment/payment_allocation
-- tables, which are milestone M5 scope and do not exist yet - until then, every bill's
-- paid amount is simply zero and its balance equals its grand total. PurchaseBillService
-- is written so plugging in real payments later needs no redesign here.

CREATE TABLE supplier (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    name                 TEXT NOT NULL,
    gstin                TEXT,
    address_line1        TEXT,
    address_line2        TEXT,
    city                 TEXT,
    pincode              TEXT,
    state_name           TEXT,
    state_code           TEXT NOT NULL,
    phone                TEXT,
    email                TEXT,
    contact_person       TEXT,
    opening_balance      INTEGER NOT NULL DEFAULT 0,
    is_active            INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    notes                TEXT,
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    updated_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

-- status DRAFT is editable with no stock effect; CONFIRMing receipt (-> RECEIVED) is what
-- creates pieces (FR-PUR-05) and is the only irreversible-without-checks step - see
-- FR-PUR-08 for why reversing back out of RECEIVED is conditional on no piece being sold.
CREATE TABLE purchase_bill (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    supplier_id          INTEGER NOT NULL REFERENCES supplier(id),
    supplier_bill_no     TEXT NOT NULL,
    bill_date            TEXT NOT NULL,
    received_date        TEXT NOT NULL,
    is_interstate        INTEGER NOT NULL CHECK (is_interstate IN (0, 1)),
    taxable_value        INTEGER NOT NULL DEFAULT 0,
    freight              INTEGER NOT NULL DEFAULT 0,
    loading_charges      INTEGER NOT NULL DEFAULT 0,
    other_charges        INTEGER NOT NULL DEFAULT 0,
    cgst_amount          INTEGER NOT NULL DEFAULT 0,
    sgst_amount          INTEGER NOT NULL DEFAULT 0,
    igst_amount          INTEGER NOT NULL DEFAULT 0,
    round_off            INTEGER NOT NULL DEFAULT 0,
    grand_total          INTEGER NOT NULL DEFAULT 0,
    status               TEXT NOT NULL CHECK (status IN ('DRAFT', 'RECEIVED', 'REVERSED')),
    notes                TEXT,
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    updated_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    UNIQUE (supplier_id, supplier_bill_no)
);

CREATE INDEX idx_purchase_bill_supplier ON purchase_bill(supplier_id, bill_date);
CREATE INDEX idx_purchase_bill_status ON purchase_bill(status);

CREATE TABLE purchase_line (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    purchase_bill_id     INTEGER NOT NULL REFERENCES purchase_bill(id),
    item_model_id        INTEGER NOT NULL REFERENCES item_model(id),
    quantity             INTEGER NOT NULL CHECK (quantity >= 1),
    rate                 INTEGER NOT NULL,
    discount_amount      INTEGER NOT NULL DEFAULT 0,
    taxable_value        INTEGER NOT NULL,
    gst_rate             NUMERIC NOT NULL,
    cgst_amount          INTEGER NOT NULL DEFAULT 0,
    sgst_amount          INTEGER NOT NULL DEFAULT 0,
    igst_amount          INTEGER NOT NULL DEFAULT 0,
    line_total           INTEGER NOT NULL
);

CREATE INDEX idx_purchase_line_bill ON purchase_line(purchase_bill_id);

CREATE TABLE purchase_return (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    purchase_bill_id     INTEGER NOT NULL REFERENCES purchase_bill(id),
    debit_note_no        TEXT NOT NULL UNIQUE,
    return_date          TEXT NOT NULL,
    reason               TEXT NOT NULL,
    taxable_value        INTEGER NOT NULL,
    cgst_amount          INTEGER NOT NULL DEFAULT 0,
    sgst_amount          INTEGER NOT NULL DEFAULT 0,
    igst_amount          INTEGER NOT NULL DEFAULT 0,
    total_amount         INTEGER NOT NULL,
    pdf_path             TEXT,
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

CREATE INDEX idx_purchase_return_bill ON purchase_return(purchase_bill_id);

-- piece_id is UNIQUE: a piece can be returned to the supplier at most once, ever.
CREATE TABLE purchase_return_line (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    purchase_return_id   INTEGER NOT NULL REFERENCES purchase_return(id),
    piece_id             INTEGER NOT NULL UNIQUE REFERENCES piece(id),
    taxable_value        INTEGER NOT NULL,
    cgst_amount          INTEGER NOT NULL DEFAULT 0,
    sgst_amount          INTEGER NOT NULL DEFAULT 0,
    igst_amount          INTEGER NOT NULL DEFAULT 0,
    line_total           INTEGER NOT NULL
);
