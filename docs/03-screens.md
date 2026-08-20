# Screens and Navigation
## Furniture Shop Inventory Management System

Companion to `01-requirements.md` and `02-data-model.md`. Version 1.0, 15 August 2026.

Every screen below lists the requirement IDs it satisfies, so the build can be checked against the SRS screen by screen.

---

## 1. Navigation map *(revised M10)*

A persistent sidebar and top bar replace the pre-M10 design's per-screen headers, each of which carried its own title, its own "Back" button, and — for eleven of the app's twenty-six screens — a duplicate way back to a screen the sidebar now reaches directly. **Stock leads**, because this is an inventory management system first; sales, purchases, money and reports are what happen *to* stock, not the subject of the app.

```
Login / Lock
   └── First-run setup wizard  (first launch only — also asks "Is your shop GST-registered?", FR-SYS-05)
          └── App shell: sidebar (left) + top bar (title, back chevron, Lock Now) + content
                │
                ├── Dashboard          ── stock-first: stock value & aging lead, sales/money below
                │
                ├── Stock              ← first in the sidebar
                │     ├── Piece register (sidebar landing) → Piece detail (full history)
                │     ├── Item models → Model editor (photos)
                │     ├── Categories & locations
                │     └── Opening stock entry
                │
                ├── Sales
                │     ├── New Sale (billing, sidebar landing)   ← the screen used most
                │     └── Invoices
                │           └── Invoice detail → Sales return · Cancel · Print · Share
                │
                ├── Bookings           *(added M13)*
                │     └── Bookings list (sidebar landing) → Booking detail → per-item delivered toggle
                │
                ├── Purchases
                │     ├── Purchase bills (sidebar landing)
                │     │     └── Bill detail → Purchase return · Reverse receipt
                │     └── Suppliers → Supplier editor
                │
                ├── Payments
                │     ├── Payment list (sidebar landing)
                │     ├── Customer receipts
                │     └── Supplier payments
                │
                ├── Reports
                │     ├── Stock on hand & valuation (sidebar landing, with aging)
                │     ├── Sales & profit
                │     └── Outstanding dues & aging
                │
                └── Settings (sidebar landing)
                      ├── Tax (GST toggle — FR-SYS-05)
                      ├── Shop profile / Invoice settings
                      ├── Backup & Restore
                      ├── Email / WhatsApp
                      ├── Categories & locations
                      ├── Audit log
                      └── Licence                *(added M14)*
```

Every sidebar button navigates directly to that section's landing screen; the top bar's back chevron returns a detail/editor screen to whichever list it was opened from (`Route.parent()`), the one thing the sidebar itself cannot express — "return to where I came from" isn't the same question as "go to a section." A screen reachable straight from the sidebar carries no chevron at all, since going "back" from it would only duplicate a sidebar button already one click away.

Design rule for the whole application, unchanged since v1: **the dashboard is never more than one click away, and New Sale is never more than two.** The sidebar satisfies both more directly than the pre-M10 design did — Dashboard and New Sale are each exactly one click from anywhere, not two.

---

## 2. Access screens

### 2.1 First-run setup wizard — `FR-AUTH-01`, `FR-SYS-01`, `FR-BAK-06`, `FR-BAK-08`, `FR-LIC-01` *(M14: five steps)*

Five steps. Steps 1-4 are skippable or gated only by their own field validations; **step 5 is not skippable** — no access to the application until activation succeeds.

| Step | Captures |
|---|---|
| 1. Shop profile | Name, address, city, pincode, state (dropdown, sets state code) — always required. **"Is your shop GST-registered?"** *(M10, FR-SYS-05)* — when yes: GSTIN and registration type (Regular / Composition), both required; when no, neither field is shown. Phone, email, logo. |
| 2. Owner login | Username, password, confirm password; displays the generated **recovery code** with an instruction to write it down and keep it off this PC |
| 3. Backup password | Password, confirm; the unmissable warning that a lost backup password makes every backup permanently unrecoverable |
| 4. Backup destination *(M12)* | A destination picker (Google Drive / OneDrive) followed by "Connect" → browser consent → shows connected account. OneDrive needs nothing typed in (built-in app registration); Google Drive still needs a client ID/secret from the owner's own Cloud project. **Skippable**, with a warning that backups will not run until connected |
| 5. Activation *(M14)* | A single activation-key field and an "Activate" button. Binds this installation to the key's licence via a signed lease from the licence server (FR-LIC-01/02). **Not skippable** — "Next" (here, "Finish") is refused until activation succeeds. The only step in the wizard that needs an internet connection at all; every other step works offline. |

Validations: state always mandatory; when GST-registered is answered yes, GSTIN format and its state-code prefix are also validated; passwords non-trivial and confirmed; step 5 requires a successful activation before setup can finish.

### 2.2 Login / Lock — `FR-AUTH-02`, `FR-AUTH-03`, `FR-AUTH-04`, `FR-AUTH-05`, `FR-AUTH-08`

Password field, "Unlock", and a "Forgot password" link leading to recovery-code entry. Increasing delay after repeated failures, shown as a countdown rather than a silent freeze.

The lock screen must **fully obscure business data** — no invoice totals or customer names peeking through — and must preserve any in-progress bill exactly as it was.

---

## 3. Dashboard — `FR-RPT-05`, `FR-BAK-11` *(restructured M10 — stock-first)*

Stock leads; sales and money sit below it. Every tile still drills through to the report or screen it summarizes.

| Section | Tile | Shows |
|---|---|---|
| **Stock** *(leads)* | Stock value | Total landed value of `IN_STOCK` pieces |
| | Pieces in stock | Count, with a sub-count of pieces aged 90+ days. Turns into an attention-seeking `.alert-surface` tile (the same treatment the stock report's own aged-stock callout uses) whenever that count is non-zero — dead stock is not an equally-weighted number next to the others |
| **Quick Actions** | New Sale · New Purchase Bill · Payments | Buttons, not tiles. Trimmed to the three that do something a plain sidebar click cannot — New Purchase Bill resets to a blank entry form, which the Purchases sidebar section (landing on the bill list) does not |
| **Sales and Money** *(secondary)* | Today's sales | Amount and invoice count |
| | This month's sales | Amount, and profit |
| | Receivable | Total customer dues, with count of overdue invoices |
| | Payable | Total supplier dues |
| | **Backup status** | Last backup time and outcome — green when recent, amber when 24–48 h, **red and persistent past 48 h** |

The pre-M10 dashboard additionally carried a "Reports" row (three buttons duplicating the sidebar's own Reports section) and a "Browse" row of seven plain navigation buttons (Item Models, Piece Register, Categories & Locations, Suppliers, Purchase Bills, Invoices, Settings) — all of it removed in M10, since the persistent sidebar now covers every one of those destinations in one click without the dashboard needing to repeat them. "Backup Now" as a separate quick-action button was removed for the same reason: it was a bare duplicate of clicking the Backup Status tile.

---

## 4. Stock screens

### 4.1 Item models — `FR-ITEM-01`, `FR-ITEM-03`, `FR-ITEM-04`, `FR-ITEM-05`

List with columns: photo thumbnail, code, name, category, HSN, GST % *(HSN and GST % columns hidden when GST is off, FR-SYS-05)*, default price, **in stock count**, active flag.
Filters: category, active/discontinued, material, colour, and dimension ranges (length/width/height between). Free-text search across name, code, material, colour.
Actions: New · Edit · Discontinue · View pieces.

Deleting a model with any piece history is refused with an explanation, offering "Discontinue" instead.

### 4.2 Item model editor — `FR-ITEM-01`, `FR-ITEM-02`

Tabs: **Details** (code, name, category, HSN, GST rate, default price, active, notes) · **Specification** (length, width, height with cm/inch toggle, material, finish, colour) · **Photos** (drag-and-drop or browse, up to 5, reorder, set primary, delete; auto-downscaled beyond 1600 px).

Validations: code unique and used as the tag prefix; GST rate 0–28.

*M10, FR-SYS-05:* HSN and GST rate are mandatory only while GST is on; both fields disappear from the Details tab entirely when it's off, and the model saves with the sentinel values (blank HSN, 0% rate) instead — a model created while GST was on keeps its real HSN/rate on record even after the toggle is switched off later, it simply stops being asked for or shown.

### 4.3 Piece register — `FR-PIECE-07`, `FR-PIECE-06`, `FR-PIECE-08`

The operational answer to "where is it and is it still ours".

Columns: tag, model, category, state (colour-coded), location, landed cost, days in stock, source document.
Filters: state, model, category, location, acquisition date range, days-in-stock bucket.
Actions: Change location · Mark damaged (reason required) · Mark repaired · Write off (reason required) · Open piece detail.
Bulk: change location for a multi-selection.

### 4.4 Piece detail — `FR-PIECE-07`, `FR-PIECE-01`, `FR-PIECE-10`

Header: tag, model with photo, current state, current location, landed cost, days held.
**Photos** section: up to 3 photos of this specific physical piece — its own condition (scuffs, wear), independent of its item model's catalogue photos (§4.2) — add / remove / set primary, same auto-downscale-beyond-1600px behaviour as model photos.
**History timeline** from `stock_movement`: received on bill X from supplier Y on date Z at cost C → moved to Godown → sold on invoice N to customer M for P, profit P−C.

This screen is what settles a physical stock discrepancy, so it must show every movement, never a summary.

### 4.5 Opening stock entry — `FR-PIECE-09`

For go-live only, but permanently available.
Rows of: model, quantity, per-piece cost, location, acquisition date (defaults to today, editable so aging is truthful).
On save: creates `quantity` individually tagged pieces per row, source `OPENING_STOCK`, and shows the generated tags so they can be written onto the furniture.

Confirmation summary before commit: "This will create 24 pieces with a total value of ₹4,80,000."

---

## 5. Purchase screens

### 5.1 Suppliers — `FR-PUR-01`, `FR-PUR-02`, `FR-PUR-09`

List: name, phone, city, state, GSTIN *(column hidden when GST is off, FR-SYS-05)*, **outstanding due**, active.
Editor: identity and address fields, state, GSTIN with format and state-prefix validation, opening balance, contact person, notes.
Supplier detail shows the bill history and running due.

*M10, FR-SYS-05:* GSTIN is GST-only and disappears from the editor entirely when GST is off. State stays on the form either way — it's ordinary address data — but it is only a *hard requirement* while GST is on, since its job there is deciding CGST/SGST vs IGST; left blank with GST off, the save fills in the shop's own state rather than being refused. A state the owner actually typed is never overwritten.

### 5.2 New purchase bill — `FR-PUR-03`, `FR-PUR-04`, `FR-PUR-05`, `FR-PUR-06`

Header: supplier, their bill number, bill date, received date. Intra/inter-state is derived from the state codes and shown, not typed.
Lines: model, quantity, rate, discount, GST rate (defaults from the model, overridable) *(hidden when GST is off)*, taxable value, tax, total.
Charges: freight, loading, other.
Totals panel: taxable value (labelled **Subtotal** when GST is off), CGST/SGST or IGST *(the whole tax block hidden as one unit when GST is off, FR-SYS-05)*, round-off, grand total.
Payment: optional payment now, with mode and reference (creates a `payment` with direction `OUT`).

**Save as draft** keeps it editable and creates no pieces. **Confirm receipt** creates the pieces — and shows a preview first: "This will create 7 pieces: 1 × Aspen Sofa at ₹18,420 each, 6 × Oak Chair at ₹3,180 each" — followed by the generated tag list to write onto the furniture.

Validations: duplicate (supplier, bill number) refused; quantity ≥ 1; a bill with no lines cannot be confirmed.

### 5.3 Purchase bills list — `FR-PUR-08`, `FR-PUR-09`

Columns: bill number, supplier, dates, total, paid, balance, status. Filters by supplier, date range, status, unpaid-only.
Bill detail offers: Record payment · Purchase return · Reverse receipt (refused, naming the blocking pieces, if any piece from the bill is sold) · Print.

### 5.4 Purchase return — `FR-PUR-07`

Pick the bill, tick the specific `IN_STOCK` pieces going back, enter reason and date. Produces a debit note, moves those pieces to `RETURNED_TO_SUPPLIER`, and reduces the supplier due.

---

## 6. Sales screens

### 6.1 New Sale — `FR-SAL-01` … `FR-SAL-09`, `FR-PAY-01`

The most important screen in the application. Fully keyboard-operable (NFR-14).

**Customer panel:** phone (typing a known number offers the existing customer), name, address, state, and — *only while GST is on, FR-SYS-05* — place of supply and GSTIN if registered. With GST off neither field is shown, and place of supply is never asked for.

**Item entry:** type a model name or scan-free tag entry → a list of that model's available `IN_STOCK` pieces with tag, location and landed cost → pick the specific piece(s). Only `IN_STOCK` pieces ever appear.

**Lines grid:** tag, model, price (pre-filled from the model default, editable), discount, taxable value and GST % and tax *(these three columns hidden when GST is off)*, line total. Pricing below landed cost shows an inline warning — visible, not blocking.

**Totals panel:** gross, discounts, taxable value (labelled **Subtotal** when GST is off), CGST/SGST **or** IGST depending on place of supply *(the whole tax block hidden as one unit when GST is off)*, round-off, grand total. A tax-inclusive toggle back-calculates from entered prices — meaningless with no tax, so it too is only shown while GST is on.

**Payment panel:** amount received now, mode, reference; balance due shown large and unmissable.

Suggested shortcuts: `F2` model search · `F4` customer field · `F9` save · `Ctrl+P` save and print · `Esc` cancel with confirmation.

On save, in one transaction: allocate the invoice number, write invoice and lines, copy each piece's landed cost into `cost_at_sale`, move pieces to `SOLD`, write stock movements, record the payment and its allocation. Any failure rolls back all of it.

Then: PDF generated → Print · WhatsApp · Email · New Sale.

### 6.2 Invoices — `FR-SAL-10`, `FR-SAL-11`, `FR-SAL-12`, `FR-DOC-02`

List: invoice number, date, customer, total, paid, balance, status. Filters by date range, customer, paid/partly/unpaid, cancelled.
Detail: full invoice with piece tags, payment history, and buttons for Record payment · Sales return · Cancel invoice (reason required) · Regenerate PDF · Print · WhatsApp · Email.

There is deliberately **no Edit button**. The screen states why: a saved tax invoice is corrected by credit note or cancellation, never by rewriting.

*M10, FR-SYS-05:* place of supply and the taxable/tax line columns on this screen are shown or hidden per **invoice**, based on whether that specific invoice actually carries any CGST/SGST/IGST — never on the live GST toggle. An invoice billed while GST was on keeps showing its real tax after the shop later turns GST off; a shop that has always billed with GST off never sees a tax column here at all. The generated PDF applies the identical rule, so the screen and the document it produces never disagree.

### 6.3 Sales return — `FR-SAL-10`

Pick the invoice, tick the pieces coming back, enter reason and date, choose whether the refund adjusts against the outstanding balance or is paid out in cash. Produces a credit note, restores those pieces to `IN_STOCK`, and reverses the corresponding tax.

### 6.4 Bookings — `FR-SAL-13` *(added M13)*

A booking is simply an `ACTIVE` invoice viewed by which of its items have been delivered — not a screen for a separate entity, and reached from its own **Bookings** sidebar section rather than nested under Invoices, since "what's still pending delivery" is a different question from "what did I sell."

**Bookings list:** invoice number, date, customer, "N of M delivered", status (Pending / Partly delivered / Delivered) — all derived, never stored. Defaults to showing only bookings with something still undelivered; a "Show fully delivered too" checkbox plus Search reveals the rest, the same filter pattern the item models and payments lists already use. A cancelled invoice never appears here.

**Booking detail:** customer, invoice number/date, "N of M delivered", and the invoice's items with a per-piece **Delivered** checkbox and a delivered-on timestamp, plus a **Mark All Delivered** button. Un-ticking is always allowed — correcting a mis-tick is fulfilment data, not an invoice edit, so FR-SAL-12's no-edit rule does not govern this screen. A piece returned via a sales return before delivery drops out of this list entirely, so a partial return can never leave a booking permanently short of Delivered.

Deferred to a later phase (see `04-roadmap.md` v2): scheduled delivery date, vehicle/driver, installation status, customer sign-off, and a dashboard tile for pending deliveries.

---

## 7. Payment screens — `FR-PAY-02` … `FR-PAY-06`

**Customer receipts:** pick customer → their unpaid invoices with balances → enter amount, date, mode, reference → allocate against one or more invoices. Over-allocation is refused, stating the maximum.

**Supplier payments:** the mirror image, against purchase bills.

**Payment list:** all payments both directions, filterable by date, party, mode; delete with a mandatory reason (soft delete, audit-logged, balances restored).

---

## 8. Reports

Common to all three: date-range picker with presets (Today, This month, Last month, This FY, Custom), on-screen table, CSV export, print.

### 8.1 Stock on hand and valuation — `FR-RPT-01`, `FR-RPT-02`

Grouped by category, then model: pieces in stock, total landed value, oldest piece age. Filters by category, location, aging bucket.
An **aging panel** shows 0–30 / 31–60 / 61–90 / 90+ day counts and values — the 90+ column is the dead-stock list, and it is highlighted.
Drill-through to the individual pieces behind any number.

### 8.2 Sales and profit — `FR-RPT-03`

Summary: invoice count, taxable value (labelled **Subtotal** when the period had no tax), tax, total sales, cost of pieces sold, **gross profit**, margin %.
Breakdowns: by day, by month, by category, by item model — sortable by profit, which is the view that answers "what should I stop stocking".
Drill-through to the invoices behind any row.

*M10, FR-SYS-05:* the Tax figure and label hide for a date range that genuinely had zero tax across every invoice in it — driven by the period's own totals, not the live toggle, so a range spanning back to when GST was on keeps showing it even after the shop turns GST off.

### 8.3 Outstanding dues and aging — `FR-RPT-04`

Customer dues grouped by customer: invoice number, date, total, paid, balance, days outstanding, aging bucket, **phone number visible for follow-up**.
Supplier dues in the same shape.
Filters: bucket, minimum amount, customer/supplier.

---

## 9. Backup and restore — `FR-BAK-01` … `FR-BAK-17`

**Status panel:** last backup time, type and outcome; next scheduled run; cloud account connected (and, for Google Drive, its target folder); count and total size of archives held in the cloud.

**Actions:** Backup now (with progress: snapshot → compress → encrypt → upload → verify → prune) · a **Backup destination** picker (Google Drive / OneDrive, FR-BAK-17) · Connect / Disconnect the selected destination · Change backup password (re-encrypts nothing retroactively — older archives keep needing the old password, and the screen says so).

**Settings:** daily time, weekly day, retention counts, and — only when Google Drive is the selected destination — Drive folder name and Google client ID/secret. *(M12)* Selecting OneDrive hides these three fields entirely, since OneDrive needs none of them (built-in app registration, fixed app folder).

**Archive list:** date, type, size, verified status, with Restore and Delete.

**Restore flow**, deliberately made slow and explicit:
1. Pick an archive → shows its date, app version and schema version
2. Warning: restoring **replaces everything currently in the system**
3. Requires typing `RESTORE` to confirm
4. Enter the backup password for that archive
5. Progress: download → decrypt → verify checksums → safety-copy current data → swap → restart
6. Any verification failure aborts cleanly and leaves current data untouched, saying exactly which step failed

---

## 10. Settings — `FR-SYS-01`, `FR-SYS-02`, `FR-SYS-03`, `FR-SYS-05`, `FR-LIC-01` *(added M14)*

| Section | Contents |
|---|---|
| **Tax** *(added M10)* | The GST-registered toggle (FR-SYS-05), with an explanation of what turning it off does. When on: the shop's state (read-only, set at setup), GSTIN, and registration type (Regular / Composition) — the one place these can be entered if the shop started with GST off and is switching it on now, since the setup wizard never collected them in that case. |
| Shop profile | Name, address, state, phone, email, logo |
| Invoice | Number series and preview, financial-year start, declaration text, signature text, tax-inclusive default, whether piece tags print under grouped lines |
| Email | SMTP host, port, TLS, username, app password, from-name, subject and body templates, **Send test email** |
| WhatsApp | Message template with placeholders (customer name, invoice number, amount) |
| Security | Idle lock timeout, change login password, regenerate recovery code |
| Lists | Categories and storage locations — add, rename, deactivate |
| Units | cm or inch for display |
| Audit log | Searchable by date, action and entity; exportable; read-only, with no edit or delete anywhere in the UI |
| **Licence** *(added M14)* | Current state (Active / Active - renewal needed soon / Read-only / Not activated) with a plain-language explanation of what it means right now; shop name, this machine's fingerprint, activation date, lease valid-until date, and the result of the last server contact; a **Check Now** button that renews the lease on demand instead of waiting for the hourly background check |

---

## 11. Cross-cutting UI rules

1. **No raw errors.** Every failure states what happened and what to do next (NFR-11).
2. **Money is right-aligned, formatted `₹1,50,000.00`**, Indian grouping, everywhere without exception (NFR-12).
3. **Dates are `dd-MM-yyyy`** everywhere.
4. **Destructive actions require a reason**: cancel invoice, write off, delete payment, restore.
5. **Every list is searchable, filterable and CSV-exportable.** No exceptions — the owner should never need to ask for an export to be added.
6. **Nothing is deleted.** Cancel, discontinue, write off, soft-delete — the history stays.
7. **The backup warning cannot be permanently dismissed.** Past 48 hours without a successful backup it returns, because the single scenario this software exists to prevent is losing the shop's records.
8. **The sidebar never steals keyboard focus** *(M10, NFR-14)*. Every nav button is excluded from tab order, so tabbing through New Sale's fields can never land in navigation instead — `Ctrl+1`…`Ctrl+7` reach the seven sidebar sections directly from the keyboard regardless of what currently has focus (shifted from six to seven when M13 added Bookings between Sales and Purchases).
9. **An idle lock blanks the screen completely, not mostly** *(M10, FR-AUTH-04)*. The lock scrim is fully opaque — the pre-M10 version left roughly 8% of it translucent, a real (if narrow) data leak once the sidebar made the screen behind the lock a permanent fixture rather than something shown once at login.
10. **Leaving a screen with unsaved work asks first.** New Sale is the one screen this currently applies to: with a persistent sidebar offering roughly eight one-click ways off any screen, a bill with at least one item added prompts for confirmation before navigating away, rather than silently discarding it.
11. **Read-only wind-down never hides data, only new writes** *(M14, FR-LIC-04)*. When the licence needs attention, a top-bar banner says so on every screen, and the handful of screens that create a new record (New Sale, Sales Return, Purchase Bill Entry, Purchase Return, Customer Receipt, Supplier Payment, Opening Stock Entry, Item Model editor, and the Bookings delivered checkbox) refuse the action with a plain-language explanation instead of opening. Every other screen — every list, every report, every PDF, CSV export, and the backup/restore pipeline — stays exactly as usable as it always was.
