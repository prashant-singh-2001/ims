-- Milestone M4 (Sales): customers, invoices and their lines, sales returns.
-- Scope matches docs/04-roadmap.md M4 and docs/02-data-model.md section 4.5.
--
-- Unlike purchase_line, sales_line is one row PER PIECE, not per quantity - the billing
-- screen sells specific tagged pieces, never "N units of a model" (FR-SAL-01), so no
-- apportionment across a line's own units is ever needed here.
--
-- "Advance at billing" (FR-PAY-01) and everything else payment-shaped is milestone M5
-- scope and does not exist yet - an invoice's balance is simply its grand_total until then,
-- the same deferral this project already made for purchase bills in M3.

CREATE TABLE customer (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    name                 TEXT NOT NULL,
    phone                TEXT NOT NULL,
    address_line1        TEXT,
    address_line2        TEXT,
    city                 TEXT,
    pincode              TEXT,
    state_name           TEXT,
    state_code           TEXT,
    gstin                TEXT,
    notes                TEXT,
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

CREATE INDEX idx_customer_phone ON customer(phone);

-- status ACTIVE/CANCELLED only - there is deliberately no "edited" state (FR-SAL-12): a
-- saved invoice is corrected by credit note or cancellation, never rewritten in place.
CREATE TABLE sales_invoice (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_no               TEXT NOT NULL UNIQUE,
    financial_year           TEXT NOT NULL,
    invoice_date             TEXT NOT NULL,
    customer_id              INTEGER NOT NULL REFERENCES customer(id),
    place_of_supply_state_code TEXT NOT NULL,
    is_interstate            INTEGER NOT NULL CHECK (is_interstate IN (0, 1)),
    is_price_inclusive       INTEGER NOT NULL CHECK (is_price_inclusive IN (0, 1)),
    gross_value              INTEGER NOT NULL DEFAULT 0,
    line_discount_total      INTEGER NOT NULL DEFAULT 0,
    bill_discount            INTEGER NOT NULL DEFAULT 0,
    taxable_value            INTEGER NOT NULL DEFAULT 0,
    cgst_amount              INTEGER NOT NULL DEFAULT 0,
    sgst_amount              INTEGER NOT NULL DEFAULT 0,
    igst_amount              INTEGER NOT NULL DEFAULT 0,
    round_off                INTEGER NOT NULL DEFAULT 0,
    grand_total              INTEGER NOT NULL DEFAULT 0,
    status                   TEXT NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    cancelled_at             TEXT,
    cancel_reason            TEXT,
    pdf_path                 TEXT,
    notes                    TEXT,
    created_at               TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    updated_at               TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

CREATE INDEX idx_sales_invoice_date ON sales_invoice(invoice_date);
CREATE INDEX idx_sales_invoice_status ON sales_invoice(status);
CREATE INDEX idx_sales_invoice_customer ON sales_invoice(customer_id);

-- description_snapshot/hsn_snapshot/gst_rate are frozen at the moment of sale so a later
-- catalogue edit never changes what a past invoice says; cost_at_sale is the piece's
-- landed cost at that same moment, frozen the same way so profit stays historically
-- accurate forever (FR-SAL-09) regardless of any later cost correction elsewhere.
CREATE TABLE sales_line (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    sales_invoice_id     INTEGER NOT NULL REFERENCES sales_invoice(id),
    piece_id             INTEGER NOT NULL UNIQUE REFERENCES piece(id),
    item_model_id        INTEGER NOT NULL REFERENCES item_model(id),
    description_snapshot TEXT NOT NULL,
    hsn_snapshot         TEXT NOT NULL,
    gst_rate             NUMERIC NOT NULL,
    unit_price           INTEGER NOT NULL,
    discount_amount      INTEGER NOT NULL DEFAULT 0,
    taxable_value        INTEGER NOT NULL,
    cgst_amount          INTEGER NOT NULL DEFAULT 0,
    sgst_amount          INTEGER NOT NULL DEFAULT 0,
    igst_amount          INTEGER NOT NULL DEFAULT 0,
    line_total            INTEGER NOT NULL,
    cost_at_sale          INTEGER NOT NULL
);

CREATE INDEX idx_sales_line_invoice ON sales_line(sales_invoice_id);

CREATE TABLE sales_return (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    sales_invoice_id     INTEGER NOT NULL REFERENCES sales_invoice(id),
    credit_note_no       TEXT NOT NULL UNIQUE,
    return_date          TEXT NOT NULL,
    reason               TEXT NOT NULL,
    taxable_value        INTEGER NOT NULL,
    cgst_amount          INTEGER NOT NULL DEFAULT 0,
    sgst_amount          INTEGER NOT NULL DEFAULT 0,
    igst_amount          INTEGER NOT NULL DEFAULT 0,
    total_amount         INTEGER NOT NULL,
    refund_mode          TEXT NOT NULL CHECK (refund_mode IN ('ADJUST_AGAINST_DUE', 'CASH_REFUND')),
    pdf_path             TEXT,
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

CREATE INDEX idx_sales_return_invoice ON sales_return(sales_invoice_id);

CREATE TABLE sales_return_line (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    sales_return_id      INTEGER NOT NULL REFERENCES sales_return(id),
    piece_id             INTEGER NOT NULL UNIQUE REFERENCES piece(id),
    sales_line_id        INTEGER NOT NULL REFERENCES sales_line(id),
    taxable_value        INTEGER NOT NULL,
    cgst_amount          INTEGER NOT NULL DEFAULT 0,
    sgst_amount          INTEGER NOT NULL DEFAULT 0,
    igst_amount          INTEGER NOT NULL DEFAULT 0,
    line_total            INTEGER NOT NULL
);
