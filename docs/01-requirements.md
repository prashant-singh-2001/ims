# Software Requirements Specification
## PieceTrack — Piece-Level Inventory Management System (Windows Desktop)

*Amended by M15 (`docs/04-roadmap.md`): this document originally specified a furniture-only
system. M15 generalized the catalogue to user-defined attributes so the same system serves any
retail business tracking individually distinguishable units. Sections below marked "M15" were
corrected at that point rather than silently carried forward; illustrative examples elsewhere
(sofas, chairs) are retained where they don't affect what the software actually requires — the
software itself no longer assumes furniture anywhere.*

| | |
|---|---|
| Version | 1.0 (draft for review) |
| Date | 15 August 2026 |
| Status | Awaiting sign-off on the open points in §7 |
| Applies to | Release v1 |

---

## 1. How to read this document

Every requirement has an ID of the form `FR-<MODULE>-nn` (functional) or `NFR-nn` (non-functional). IDs are permanent — if a requirement is dropped later, its ID is retired, never reused, so that test cases and screens keep pointing at the right thing.

Requirements are written as **"The system shall…"**. Anything phrased as *should* or *may* is a preference, not a commitment, and can be dropped without breaking the release.

Nothing in this document was invented. Every decision traces back to an answer given during requirements gathering; §6 lists the traces. Where a decision was genuinely not made, it appears in §7 as an open point instead of being quietly assumed.

Companion documents:
- `02-data-model.md` — tables, relationships, the per-piece lifecycle
- `03-screens.md` — every screen, its fields and the requirements it satisfies
- `04-roadmap.md` — what lands in v1 versus later

---

## 2. Product overview

### 2.1 Problem

A retail shop buys finished goods from suppliers and sells them to walk-in customers. Today, stock, purchase bills, customer bills and pending payments are tracked on paper and in memory. Consequences: nobody knows exactly what is in the storeroom, profit per sale is guesswork because purchase costs vary between consignments, and outstanding customer balances are remembered rather than recorded.

### 2.2 Goal

A single Windows application, running on one shop PC, that is the authoritative record of:

1. every physical piece of stock the shop owns, and what it cost;
2. every purchase from a supplier and what is still owed on it;
3. every sale to a customer, as a GST-compliant invoice, and what is still owed on it;
4. a nightly, encrypted, off-site copy of all of the above in Google Drive or OneDrive.

The defining characteristic of this system is **piece-level tracking**. The shop does not count "4 chairs" or "4 rings"; it tracks four individual units, each with its own tag, its own purchase cost, and its own physical location. This is what makes true profit-per-sale possible, and it shapes the entire data model. What each item is *called* and what fields describe it (dimensions for furniture, carat for jewellery, voltage for appliances) is entirely up to the shop — the software imposes no fixed product shape *(M15: previously a fixed furniture-shaped form; see FR-ITEM-01)*.

### 2.3 In scope for v1

- Item model catalogue with user-defined attributes, photos
- Piece register — every physical unit individually tagged and costed
- Supplier master and purchase bills, with pieces created on receipt
- Opening stock entry (the shop starts fresh; existing stock is keyed in)
- GST sales invoicing with correct CGST/SGST/IGST treatment
- Customer advances, part-payments, and outstanding balances
- Supplier dues and payments against purchase bills
- Sales returns and purchase returns
- Invoice PDF generation, WhatsApp share and email send
- Three reports: stock on hand & valuation; sales & profit; outstanding dues & stock aging
- Password login with idle auto-lock
- Scheduled encrypted backup to Google Drive or OneDrive, with in-app restore

### 2.4 Explicitly out of scope for v1

These are **not oversights**. Each was considered and deferred:

| Not building | Why |
|---|---|
| Customer CRM, enquiry follow-up, quotations | Deferred to a later phase |
| Delivery and installation tracking | Deferred to a later phase |
| Multi-user access, staff logins, LAN sharing | Single PC, single user |
| Multiple branches, godown-to-godown transfers | Single location |
| Manufacturing, bill of materials, raw material stock | Retail only — stock is bought finished, not assembled on-site |
| Wholesale price lists, dealer credit terms | Retail counter sales only |
| GSTR-1 / GSTR-3B export files | GST depth limited to compliant invoices + summaries |
| E-invoice (IRN/QR) and e-way bill portal integration | Not required at current turnover |
| Full accounting — ledgers, trial balance, P&L, bank reconciliation | This is an inventory system, not accounting software |
| Barcode scanners, label printers, thermal printers | No hardware integration requested |
| Import from Excel or Tally | Starting fresh; data is keyed in |
| Cloud sync, mobile app, web access | Backup to Drive is the only online element |

### 2.5 Actors

| Actor | Description |
|---|---|
| **Shop Owner** | The sole user of the system. Full access to everything, including costs, margins and reports. |
| Customer | A data subject, not a user. Never touches the software; receives a PDF invoice. |
| Supplier | A data subject, not a user. Purchase bills are entered on their behalf. |
| Google Drive / OneDrive | External system. Receives encrypted backup archives, over the Drive API or Microsoft Graph, depending on which is the active backup destination (FR-BAK-17). |

### 2.6 Glossary

| Term | Meaning in this system |
|---|---|
| **Item model** | A type of product the shop deals in — e.g. "3-seater recliner sofa, brown leatherette" for a furniture shop, or "18K gold ring, size 14" for a jeweller. Carries HSN, GST rate, photos, and whatever attributes the owner has defined (§FR-ITEM-01, M15). Holds no stock itself. |
| **Attribute definition** | *(M15)* A field the owner has added to describe item models — e.g. "Material", "Warranty", "Voltage" — managed from Settings, not hardcoded. Replaces the fixed dimension/material/finish/colour fields earlier versions carried. |
| **Piece** | One physical unit of an item model, with a unique tag. This is what is counted, costed and sold. |
| **Tag** | The human-readable unique identifier written or stuck on a physical piece, e.g. `SOF-0042`. |
| **Landed cost** | What one specific piece actually cost the shop, after apportioning the purchase bill's freight and other charges across the pieces on it. |
| **Purchase bill** | A supplier's invoice for goods received. Receiving it creates pieces. |
| **Opening stock** | Stock already in the shop when the software goes live, entered without a supplier bill. |
| **HSN** | Harmonised System of Nomenclature — the GST classification code printed on invoices. Wooden furniture generally falls under 9403. |
| **Place of supply** | The state where the sale is treated as occurring. Decides CGST+SGST versus IGST. |
| **Advance** | Money taken from a customer at or before billing, before full settlement. |
| **Due / outstanding** | Invoice total minus payments received against it. |
| **Aging** | How long a due has been unpaid, or how long a piece has sat unsold, in 0–30 / 31–60 / 61–90 / 90+ day buckets. |

---

## 3. Functional requirements

### 3.1 AUTH — Access control

The PC sits in a shop. Costs, margins and customer dues must not be visible to anyone who walks past it.

**FR-AUTH-01 — First-run setup**
The system shall, on first launch, run a setup wizard that captures: shop profile (§3.10), the owner's login password, and the backup encryption password. The application shall not be usable until this wizard completes.

*Acceptance:* launching a fresh install goes to the wizard, not the dashboard; closing the wizard early re-opens it on next launch.

**FR-AUTH-02 — Password login**
The system shall require the login password at every start. The password shall be stored only as a salted hash (Argon2id, or bcrypt cost ≥ 12), never in plain or reversible form.

*Acceptance:* a wrong password is rejected; the stored value in the database is not the typed password.

**FR-AUTH-03 — Failed attempt throttling**
The system shall introduce an increasing delay after consecutive failed attempts (no delay for the first 3, then 5s, 15s, 60s). It shall not permanently lock the owner out.

**FR-AUTH-04 — Idle auto-lock**
The system shall lock the screen after a configurable idle period (default 10 minutes, range 1–120, or "never"), blanking all business data behind a lock screen. Unlocking requires the login password. Any in-progress bill shall be preserved intact across a lock.

*Acceptance:* leave a half-entered bill idle past the timeout; after unlocking, the bill is exactly as it was.

**FR-AUTH-05 — Manual lock**
The system shall provide a lock command (menu item and keyboard shortcut) that locks immediately.

**FR-AUTH-06 — Change password**
The system shall allow changing the login password after confirming the current one.

**FR-AUTH-07 — Role structure present, single role used**
The system shall model users and roles in the database from v1, with exactly one user of role `OWNER` created at setup, so that staff logins can be added later without a data migration. No user management UI is built in v1.

**FR-AUTH-08 — Password recovery** *(see open point §7.3)*
The system shall provide a documented recovery path for a forgotten login password.

---

### 3.2 ITEM — Item model catalogue

**FR-ITEM-01 — Create item model** *(revised M15)*
The system shall let the owner define an item model with:

| Field | Required | Notes |
|---|---|---|
| Model name | Yes | e.g. "Aspen 3-Seater Sofa" or "18K Gold Ring, size 14" |
| Model code | Yes | Short unique code, used as the tag prefix |
| Category | Yes | Owner-defined, editable list — no categories are pre-seeded on a fresh install |
| HSN code | Yes | Printed on the invoice |
| GST rate % | Yes | Per model, never hard-coded (see §7.2) |
| Custom attributes | No | *(M15)* Owner-defined fields (Material, Warranty, Voltage, Dimensions, Carat — anything), managed as a list from Settings and rendered as a form on this screen. Replaces the fixed Dimensions/Material/Finish/Colour fields earlier versions hard-coded. |
| Default selling price | No | Pre-fills the billing screen; always overridable |
| Photos | No | Up to 5 images |
| Active / discontinued | Yes | Defaults to active |
| Notes | No | Free text |

**FR-ITEM-02 — Photos**
The system shall accept JPG and PNG images per model, store them on disk (not as database blobs), downscale anything larger than 1600px on the long edge, and display them in the item screen. Photos shall be included in the backup archive (FR-BAK-03).

**FR-ITEM-03 — Edit and discontinue**
The system shall allow editing any model field. A model with pieces ever recorded against it shall not be deletable — it can only be marked discontinued, which hides it from new billing and receiving but preserves history.

*Acceptance:* attempting to delete a model that has been sold gives a clear refusal explaining why; discontinuing it removes it from the billing search.

**FR-ITEM-04 — Search** *(corrected M15)*
The system shall let the owner find models by name, code, category, or the value of any custom attribute — the search a customer's question actually triggers.

*Correction, M15:* earlier versions of this document also promised search by dimension range (e.g. "beds between 180 and 200 cm long"). No screen ever implemented it — `ItemModelSearchCriteria`'s dimension fields existed but were always passed `null` — and the dead fields have now been deleted from the code rather than carried forward as an unfulfilled promise. Dimensions are, in any case, no longer a fixed field: a shop that wants to filter by them can search their free-text value like any other custom attribute.

**FR-ITEM-05 — Stock visibility on the model**
The system shall show, for each model, the live count of pieces currently `IN_STOCK`, derived from the piece register rather than stored as a number.

---

### 3.3 PIECE — The piece register

This is the core of the system.

**FR-PIECE-01 — One record per physical unit**
The system shall create exactly one piece record for each physical furniture unit the shop owns, carrying: unique tag, item model, source (purchase bill or opening stock), landed cost, storage location, state, and date acquired.

**FR-PIECE-02 — Tag generation**
The system shall auto-generate the tag as `<model code>-<zero-padded sequence>` (e.g. `SOF-0042`), guaranteed unique across the whole database, and shall allow the owner to override it with their own value provided it stays unique.

**FR-PIECE-03 — Piece states**
The system shall maintain each piece in exactly one state:

| State | Meaning |
|---|---|
| `IN_STOCK` | Physically present and available to sell |
| `SOLD` | On a sales invoice that is not cancelled |
| `RETURNED_TO_SUPPLIER` | Sent back on a purchase return |
| `DAMAGED` | Present but not saleable |
| `WRITTEN_OFF` | Removed from stock permanently |

**FR-PIECE-04 — Legal state transitions**
The system shall permit only these transitions, and shall reject all others with a clear message:

```
IN_STOCK  → SOLD                  (sale)
IN_STOCK  → RETURNED_TO_SUPPLIER  (purchase return)
IN_STOCK  → DAMAGED               (manual)
IN_STOCK  → WRITTEN_OFF           (manual, with reason)
SOLD      → IN_STOCK              (sales return, or invoice cancellation)
DAMAGED   → IN_STOCK              (repaired, manual)
DAMAGED   → WRITTEN_OFF           (manual, with reason)
```

*Acceptance:* a piece already `SOLD` cannot be added to a second invoice; the billing screen never offers it.

**FR-PIECE-05 — Landed cost is immutable per piece**
The system shall record each piece's landed cost at the moment of receipt and shall not recalculate it afterwards, even if the purchase bill is later edited. Editing a received bill requires reversing the receipt (FR-PUR-08).

**FR-PIECE-06 — Storage location** *(revised M15)*
The system shall record where each piece physically is, chosen from an editable location list defined entirely by the owner (no locations are pre-seeded on a fresh install — see FR-ITEM-01's note on categories, same reasoning). The location of an `IN_STOCK` piece shall be changeable at any time.

**FR-PIECE-07 — Piece search**
The system shall let the owner find a piece by tag, model, location, state, or acquisition date range, and shall show its full history — received on which bill, moved where, sold on which invoice, at what price and profit.

**FR-PIECE-08 — Manual state change with reason**
The system shall require a text reason when marking a piece `DAMAGED` or `WRITTEN_OFF`, and shall record it in the audit log with the date.

**FR-PIECE-09 — Opening stock**
The system shall provide an opening-stock entry screen that creates pieces without a supplier bill, capturing model, quantity, per-piece cost (the owner's best estimate) and location, and marking their source as `OPENING_STOCK`.

*Acceptance:* entering quantity 6 against a dining chair model creates 6 individually tagged pieces, each costed and each independently sellable.

**FR-PIECE-10 — Photos**
The system shall accept JPG and PNG images per piece, store them on disk (not as database blobs), downscale anything larger than 1600px on the long edge, and display them on the piece detail screen. A piece's photos document that specific physical unit's condition and are independent of its item model's own photos (FR-ITEM-02) — two pieces of the same model can show different wear.

---

### 3.4 PUR — Suppliers, purchases and receipt

**FR-PUR-01 — Supplier master**
The system shall maintain suppliers with: name (required), GSTIN, address, state (required — it decides the tax split), phone, email, contact person, opening balance owed, notes.

**FR-PUR-02 — GSTIN validation**
The system shall validate any entered GSTIN against the standard 15-character format and check that its first two digits match the selected state code, warning on mismatch without blocking.

**FR-PUR-03 — Purchase bill entry**
The system shall record a supplier's bill with: supplier, their bill number, bill date, received date, line items (item model, quantity, rate, discount, GST rate), and bill-level charges (freight, loading, other), producing taxable value, tax amount and grand total.

**FR-PUR-04 — Tax split on purchase**
The system shall compute input tax as CGST + SGST when the supplier's state equals the shop's state, and as IGST otherwise.

**FR-PUR-05 — Piece creation on receipt**
The system shall, on confirming receipt of a bill, create one piece per unit of quantity on every line — quantity 5 creates 5 pieces — each with its own tag and each set to `IN_STOCK`.

*Acceptance:* a bill with 1 sofa and 6 chairs produces exactly 7 piece records.

**FR-PUR-06 — Landed cost apportionment**
The system shall compute each piece's landed cost as its line's per-unit taxable value (after line discount) plus a share of bill-level charges apportioned by line value, with any rounding remainder assigned to the last piece so the sum of landed costs equals the apportioned bill total exactly.

Cost basis excludes GST, because input tax is credit, not cost (see §7.4).

*Acceptance:* sum of all landed costs on a bill equals bill taxable value + charges, to the paisa.

**FR-PUR-07 — Purchase return**
The system shall allow returning specific `IN_STOCK` pieces to their supplier, moving them to `RETURNED_TO_SUPPLIER`, recording a debit note with reason and date, and reducing the amount owed to that supplier.

**FR-PUR-08 — Correcting a received bill**
The system shall allow a received bill to be corrected only by reversing the receipt first, and shall refuse the reversal if any piece from that bill has been sold — naming the pieces that block it.

**FR-PUR-09 — Supplier dues**
The system shall track, per purchase bill, the amount payable, the amount paid and the balance outstanding, and shall show the total owed per supplier.

---

### 3.5 SAL — Sales and GST invoicing

**FR-SAL-01 — Billing screen**
The system shall let the owner build an invoice by searching for a model and picking specific `IN_STOCK` pieces, or by entering a tag directly. Only pieces in `IN_STOCK` shall be selectable.

**FR-SAL-02 — Customer capture**
The system shall capture, per invoice: customer name (required), phone (required — it drives WhatsApp sharing), address, state and place of supply, and GSTIN if the customer is registered. Re-entering a known phone number shall offer to reuse the existing customer record.

**FR-SAL-03 — Pricing and override**
The system shall pre-fill each line's price from the model's default selling price and shall allow overriding it. Where the final price is below the piece's landed cost, the system shall warn before saving (it shall not block — a clearance sale is legitimate).

**FR-SAL-04 — Discounts**
The system shall support a per-line discount (amount or percentage) and a bill-level discount, applying bill-level discount proportionally across lines so per-line tax stays correct.

**FR-SAL-05 — GST computation**
The system shall compute tax per line at that model's GST rate, splitting into CGST + SGST when the place of supply equals the shop's state, and charging IGST otherwise. Prices shall be treated as tax-exclusive by default, with a per-invoice option to treat entered prices as tax-inclusive and back-calculate.

*Acceptance:* an intra-state sale of ₹10,000 at 18% shows CGST ₹900 + SGST ₹900; the same sale to another state shows IGST ₹1,800.

**FR-SAL-06 — Rounding**
The system shall round the invoice total to the nearest rupee and show the rounding adjustment as its own line.

**FR-SAL-07 — Invoice numbering**
The system shall issue consecutive invoice numbers from a configurable series (default `INV/<FY>/nnnn`, e.g. `INV/26-27/0001`), unique within the financial year, resetting on 1 April, with no gaps and no reuse. Numbers shall be allocated only on save, never on screen entry that might be abandoned.

*Acceptance:* abandoning a half-typed bill does not consume a number; saved invoices are strictly sequential.

**FR-SAL-08 — Effect on stock**
The system shall move every piece on a saved invoice to `SOLD` in the same database transaction as the invoice itself. A failure at any point shall leave neither the invoice nor the stock change in place.

**FR-SAL-09 — Profit capture**
The system shall record, per invoice line, the piece's landed cost at the time of sale, so that profit remains historically accurate regardless of later changes.

**FR-SAL-10 — Sales return**
The system shall support returning specific pieces from an invoice, generating a credit note, restoring those pieces to `IN_STOCK`, reversing the corresponding tax, and adjusting the customer's outstanding balance or recording a refund.

**FR-SAL-11 — Cancellation**
The system shall allow cancelling an invoice, which restores all its pieces to `IN_STOCK` and reverses payments allocated to it. A cancelled invoice shall be retained and clearly marked cancelled — never deleted, and its number never reused.

**FR-SAL-12 — No edit after save**
The system shall not permit editing a saved invoice. Corrections go through cancellation or a credit note. This is deliberate: a tax invoice that can be silently rewritten is not a record.

**FR-SAL-13 — Delivery tracking** *(added M13)*
The system shall let the owner record, per invoice line, whether that piece has physically reached the customer, and shall present every `ACTIVE` invoice with at least one undelivered line as a "booking" in a dedicated Bookings screen. A booking's status (Pending / Partly delivered / Delivered) is derived from its lines' delivered state at read time, never stored. Marking a line undelivered again (correcting a mis-tick) is always permitted and is not subject to FR-SAL-12, since it corrects fulfilment data recorded alongside the invoice, not the invoice itself. A cancelled invoice is never a booking. A piece returned via FR-SAL-10 before delivery is excluded from its invoice's delivery counts entirely, so a partial return can never leave a booking permanently unable to reach Delivered.

Scheduled delivery date, vehicle/driver assignment, installation status and customer sign-off are deferred to a later phase (see `04-roadmap.md` v2), along with the `AWAITING_DELIVERY` piece state that phase introduces — FR-SAL-13 deliberately tracks only the delivered/not-delivered fact, leaving `Piece.State` unchanged (a delivered piece is still `SOLD`).

---

### 3.6 PAY — Money in and money out

**FR-PAY-01 — Advance at billing**
The system shall accept a payment at the moment of invoicing, of any amount from zero to the invoice total, leaving the remainder as outstanding.

**FR-PAY-02 — Later receipts**
The system shall record further payments against an invoice at any time, with date, amount, mode (Cash / UPI / Card / Bank transfer / Cheque), reference number and note.

**FR-PAY-03 — Outstanding balance**
The system shall maintain, per invoice, the running balance = total − payments + credit notes, and shall mark it Paid, Partly paid or Unpaid accordingly.

**FR-PAY-04 — Overpayment**
The system shall refuse a payment that would exceed an invoice's outstanding balance, stating the maximum acceptable amount.

**FR-PAY-05 — Supplier payments**
The system shall record payments made to suppliers against specific purchase bills, with the same fields as customer receipts, and shall maintain each bill's outstanding balance and each supplier's total due.

**FR-PAY-06 — Payment deletion**
The system shall allow deleting a wrongly entered payment, restoring the affected balance and writing the deletion to the audit log with date and reason.

---

### 3.7 DOC — Invoice PDF, WhatsApp and email

**FR-DOC-01 — GST tax invoice PDF**
The system shall generate an A4 PDF containing every field a GST tax invoice requires:

- Heading "TAX INVOICE"; shop name, address, GSTIN, state and state code; shop logo if configured
- Invoice number, invoice date, place of supply
- Customer name, address, state, and GSTIN when registered
- Per line: description, HSN, quantity, unit, rate, discount, taxable value, GST rate, tax amount
- Tax summary grouped by HSN and rate, split CGST / SGST or IGST
- Total taxable value, total tax, rounding, grand total
- Grand total in words, in Indian numbering (lakh / crore)
- "Reverse charge applicable: No"
- Amount paid and balance due
- Declaration text and signature block, both configurable in settings

**FR-DOC-02 — PDF storage**
The system shall save every generated PDF under the application data folder in a `Invoices/<FY>/` structure named by invoice number, and shall be able to regenerate a PDF for any past invoice on demand.

**FR-DOC-03 — WhatsApp share**
The system shall open WhatsApp (desktop app or WhatsApp Web) addressed to the customer's phone number, with a configurable message template pre-filled, and shall simultaneously open the folder containing the PDF so it can be attached.

**Known limitation, accepted:** a free `wa.me` link cannot attach a file — WhatsApp does not permit it. The attachment step is manual. Fully automatic PDF delivery over WhatsApp would require the paid WhatsApp Business API, which is out of scope. Email (FR-DOC-04) attaches automatically.

**FR-DOC-04 — Email send**
The system shall send the invoice PDF as an email attachment through the owner's configured SMTP account (Gmail with an app password, or any SMTP host), with a configurable subject and body template, and shall report success or failure clearly.

**FR-DOC-05 — Credit and debit note documents**
The system shall generate equivalent PDFs for credit notes (sales returns) and debit notes (purchase returns).

**FR-DOC-06 — Line grouping on the printed invoice**
Because every piece is a separate invoice line internally (FR-PIECE-01), the system shall group lines sharing the same model, unit price and GST rate into a single printed line showing the quantity — so six chairs print as "6 × Dining Chair", not as six identical rows. A setting shall control whether the individual piece tags are listed beneath the grouped line. Grouping affects presentation only; the stored per-piece detail is unchanged.

---

### 3.8 RPT — Reports

All reports shall support a date range where meaningful, shall be viewable on screen, and shall export to CSV.

**FR-RPT-01 — Stock on hand and valuation**
The system shall report every `IN_STOCK` piece grouped by model and category, showing count and total landed value, with the grand total of money currently tied up in stock. Filterable by category and location. Drillable to individual pieces.

**FR-RPT-02 — Stock aging**
The system shall show, within the stock report, how long each piece has been in stock in 0–30 / 31–60 / 61–90 / 90+ day buckets, so slow-moving and dead stock is visible.

**FR-RPT-03 — Sales and profit**
The system shall report, for a chosen period, invoice count, total taxable value, total tax, total sales, total cost of pieces sold, and gross profit — with per-day and per-month totals, and a breakdown by item model and by category showing which lines actually make money.

*Acceptance:* profit for a sale equals sale taxable value minus the sold pieces' recorded landed costs, and does not change if the model's cost or price is edited afterwards.

**FR-RPT-04 — Outstanding dues and aging**
The system shall report all invoices with a balance due, aged 0–30 / 31–60 / 61–90 / 90+ days from invoice date, grouped by customer with contact numbers for follow-up, plus the equivalent for supplier dues.

**FR-RPT-05 — Dashboard**
The system shall show on the home screen: today's and this month's sales, stock value, count of pieces in stock, total receivable, total payable, count of pieces aged over 90 days, and last backup status.

---

### 3.9 BAK — Backup and restore

The stated reason this software exists on one PC with no server: the data must survive that PC dying.

**FR-BAK-01 — Schedule**
The system shall run a **daily** backup at a configurable time (default 21:30) and a **weekly** backup on a configurable day (default Sunday), automatically, without the owner doing anything.

**FR-BAK-02 — Catch-up run**
The system shall detect a missed scheduled backup — the usual case being that the shop PC was switched off at that hour — and run it shortly after the next application start.

*Acceptance:* keep the PC off for three days; on next start, a backup runs and the status shows it.

**FR-BAK-03 — What is backed up**
Each archive shall contain a consistent snapshot of the database **and** the photo and invoice-PDF folders, plus a manifest recording app version, schema version, creation time, type (daily/weekly) and content checksums.

This matters: photos live on disk and are only referenced by the database. A backup of the database alone would silently lose them.

**FR-BAK-04 — Consistent snapshot**
The system shall take the database snapshot through the database engine's own backup mechanism, so a backup running while the application is open cannot capture a half-written state.

**FR-BAK-05 — Encryption**
The system shall encrypt every archive with AES-256-GCM, using a key derived from the owner's backup password by a memory-hard KDF (Argon2id, or PBKDF2-HMAC-SHA256 at ≥ 210,000 iterations) with a per-archive random salt. Only the format version, KDF parameters and salt shall be readable in the clear.

**FR-BAK-06 — Backup password handling**
The system shall require the backup password to be entered twice at setup, shall verify it, and shall display an unmissable warning that **if this password is lost, every backup becomes permanently unrecoverable — Google cannot help, and neither can the software**. The password shall never be stored in recoverable form and shall never be uploaded.

**FR-BAK-07 — Upload to a cloud backup destination**
The system shall support two interchangeable cloud destinations, one active at a time: Google Drive, uploading each archive to a dedicated folder in the owner's Drive (default name `FurnitureShopBackups`, configurable) via Drive API v3 with the `drive.file` scope; and OneDrive *(added M12)*, uploading to this application's dedicated app folder via Microsoft Graph's `Files.ReadWrite.AppFolder` scope. Both scopes grant access only to files this application itself created — never to the rest of the owner's Drive or OneDrive. Switching the active destination does not affect archives already uploaded under the other one — each `backup_history` row records which destination it actually went to, so restore always reaches the right place.

**FR-BAK-08 — Cloud account authorisation**
The system shall authorise Google Drive via the OAuth 2.0 desktop loopback flow (client ID/secret from the owner's own Google Cloud project — see section 5), and OneDrive *(added M12)* via the OAuth 2.0 authorization-code-with-PKCE flow against a single Azure app registration built into the application, so connecting OneDrive needs nothing typed in beyond signing in. Both flows open the browser once for consent and store the resulting refresh token encrypted with the Windows DPAPI under the current Windows user account. The system shall detect a revoked or expired grant and prompt to re-authorise.

**FR-BAK-09 — Retention**
The system shall keep the most recent **14 daily** and **12 weekly** archives, deleting older ones from the active cloud destination automatically, so storage does not grow without bound. Both limits shall be configurable.

**FR-BAK-10 — Manual backup**
The system shall provide a "Backup now" action that runs the full pipeline immediately and shows progress.

**FR-BAK-11 — Status and failure visibility**
The system shall show the last backup's time and outcome on the dashboard at all times, and shall raise a persistent, dismissible-but-recurring warning when no backup has succeeded for more than 48 hours. A silent backup failure is treated as a defect, not an inconvenience.

**FR-BAK-12 — Offline tolerance**
The system shall, when the internet is unavailable, still produce the encrypted archive locally, retain it, and upload it on the next successful connection. No business function other than upload, email and WhatsApp share shall depend on connectivity.

**FR-BAK-13 — Restore**
The system shall list available archives, from local disk and from whichever cloud destination each was actually uploaded to, with their date, type and size, and shall restore a chosen one: download (if not already local) → decrypt → verify checksums → unpack to a staging folder → **take a safety copy of the current data** → swap in the restored data → restart.

**FR-BAK-14 — Restore safety**
The system shall never overwrite live data in place, shall require explicit typed confirmation that restoring replaces everything currently in the system, and shall abort cleanly leaving the existing data untouched if any verification step fails.

**FR-BAK-15 — Schema version check**
The system shall refuse to restore an archive whose schema version is newer than the running application, and shall run forward migrations when it is older.

**FR-BAK-16 — Local copy**
The system shall additionally retain the last 3 archives on the local disk, so recovery from an accidental deletion does not require internet access.

**FR-BAK-17 — Backup destination selection** *(added M12)*
The system shall let the owner choose the active cloud destination (Google Drive or OneDrive) from Settings and from the first-run setup wizard. Selecting OneDrive shall hide the Google-specific client ID/secret/folder-name fields entirely, since OneDrive needs none of them.

---

### 3.10 SYS — Shop profile, settings, audit

**FR-SYS-01 — Shop profile**
The system shall store shop name, address, state and state code, GSTIN, phone, email, logo image, and the invoice declaration and signature text — all appearing on generated documents.

**FR-SYS-02 — Settings**
The system shall expose in one place: backup schedule, retention counts, backup destination (Google Drive or OneDrive, FR-BAK-17), Drive folder name (Google only), cloud account status, SMTP settings, WhatsApp message template, idle-lock timeout, invoice number series, financial year start, the category, location and attribute-definition lists (attribute definitions added M15), and licence status (FR-LIC-01, added M14).

*Corrected M15:* earlier versions of this document listed a "default units (cm/inch)" setting here. It was never implemented — dimensions were a fixed field, but no cm/inch toggle existed anywhere in the running application — and is dropped from this list rather than carried forward as an unfulfilled promise. A shop that wants to record a dimension does so as free text in a custom attribute, in whatever unit it prefers.

**FR-SYS-03 — Audit log**
The system shall record every consequential action — invoice created/cancelled, piece state changed, payment recorded/deleted, cost or price overridden, restore performed, settings changed — with timestamp, action, entity, and before/after values where applicable. The log shall be viewable and exportable, and shall not be editable from within the application.

**FR-SYS-04 — Financial year awareness**
The system shall treat the financial year as 1 April to 31 March for invoice numbering and period reports.

**FR-SYS-05 — GST optional** *(added M10)*
The system shall let the owner turn GST off, for a shop that is not GST-registered. Asked once at first-run setup, changeable later from Settings. With GST off:

- purchase and sales screens collect no HSN code, GST rate, GSTIN or place of supply, and compute no CGST/SGST/IGST — every bill is a plain total with no tax line;
- generated documents print a plain "INVOICE" heading (never "TAX INVOICE" or "BILL OF SUPPLY"), and carry no GSTIN, HSN, place-of-supply or tax-summary section;
- a document or screen for a record that was created while GST **was** on continues to show its real historical tax exactly as billed, regardless of the toggle's current position — turning GST off does not rewrite invoices already issued;
- turning GST on again asks for the shop's GSTIN and registration type at that point, since a shop that started with GST off never had them collected.

The shop's state remains a required field either way — it is ordinary address data that prints on every document regardless of GST.

*Acceptance:* with GST off, an item model saves with no HSN or GST rate, a sale produces a plain bill with `grand_total == taxable_value` and zero tax, and the generated PDF contains neither "GSTIN" nor "HSN" nor "CGST" nor "Place of Supply". With GST on, every existing GST computation is unchanged.

---

### 3.11 LIC — Licensing and activation *(added M14)*

**FR-LIC-01 — Activation**
The system shall require a valid activation key, entered as the fifth and final step of first-run setup, before setup can complete. The key binds this installation to a signed lease issued by the licence server; setup cannot finish without a successful activation.

**FR-LIC-02 — Machine binding**
The system shall derive a fingerprint from a stable, OS-level machine identifier (the Windows `MachineGuid`) and bind the activation key to it. An activation key already bound to a different machine's fingerprint shall be refused, and the attempt recorded on the server for the licence owner to see.

**FR-LIC-03 — Offline lease**
The system shall hold a signed, time-limited lease (30 days) that lets it operate fully offline for the full lease window with no server contact at all. The lease shall be renewed silently in the background whenever connectivity is available, well before it expires.

**FR-LIC-04 — Read-only wind-down**
If the lease is missing, expired, revoked, signed by an unrecognised key, or bound to a different machine's fingerprint, the system shall enter a read-only state: creating a new invoice, purchase bill, payment, or opening-stock entry shall be refused with a plain-language explanation (NFR-11). Every already-recorded record, every report, PDF regeneration, CSV export, and the backup/restore pipeline shall remain fully available — read-only wind-down shall never withhold the shop's own data.

**FR-LIC-05 — Revocation**
The licence owner shall be able to revoke a licence remotely. A revoked installation shall stop renewing its lease and enter read-only wind-down once the currently held lease expires, with no action needed on the installation itself.

**FR-LIC-06 — Owner notification**
The licence server shall record every activation and every rejected activation attempt (a key already bound elsewhere), viewable by the licence owner, so a second, unauthorised installation is discoverable.

*Acceptance:* a fresh install cannot pass the setup wizard without a valid, unbound activation key; a second installation activated with the same key as an already-bound one is refused and the attempt is recorded on the server; an installation with no internet connectivity at all continues normal operation for its full lease window; a revoked installation becomes read-only after its lease expires while its reports, PDFs, CSV export, and backup remain fully available.

---

## 4. Non-functional requirements

**NFR-01 — Platform.** Windows 10 and Windows 11, 64-bit. No other OS is supported or tested.

**NFR-02 — Installation.** A single signed-or-unsigned Windows installer that requires no separate Java installation, no database server, and no manual configuration beyond the first-run wizard.

**NFR-03 — Data location.** All business data, photos, PDFs, logs and local backups under `%LOCALAPPDATA%\PieceTrack\` *(renamed from `FurnitureIMS`, M15 — an existing install's data folder is migrated automatically on first launch of the new build, never left behind)*. Nothing written inside Program Files.

**NFR-04 — Performance targets**, on ordinary shop-PC hardware (4 GB RAM, spinning disk), at a realistic scale of 20,000 pieces and 20,000 invoices:

| Operation | Target |
|---|---|
| Application start to login screen | ≤ 5 s |
| Any search result | ≤ 1 s |
| Saving an invoice | ≤ 2 s |
| Generating an invoice PDF | ≤ 3 s |
| Any report over one year of data | ≤ 5 s |

**NFR-05 — Data integrity.** Every operation touching more than one table — invoice + piece states + payment — shall be a single atomic transaction. A crash mid-operation shall leave the database consistent.

**NFR-06 — Offline first.** Every function except Drive/OneDrive upload, email send, WhatsApp share, and licence activation/renewal (M14, FR-LIC-03) shall work with no internet connection. *Amended by M14:* a one-time licence activation needs connectivity, but once activated the app works fully offline for the full 30-day lease window (FR-LIC-03) - connectivity is only ever required to renew, never to operate.

**NFR-07 — Concurrency.** Exactly one instance of the application shall run at a time; starting a second shall focus the first rather than opening a second connection to the database.

**NFR-08 — Schema migrations.** Schema changes shall be applied by versioned, forward-only migration scripts run automatically at startup, so upgrading the application never requires manual database work.

**NFR-09 — Upgrade safety.** The installer shall preserve all existing data, and the application shall take an automatic local backup before applying any schema migration.

**NFR-10 — Logging.** Rolling application logs (30 days) capturing errors with stack traces and all backup activity. Logs shall never contain the login password, the backup password, or OAuth tokens.

**NFR-11 — Error handling.** No raw exception or stack trace shall ever reach the owner's screen. Errors shall be stated in plain language, saying what failed and what to do next.

**NFR-12 — Localisation.** Indian conventions throughout: ₹ currency, Indian digit grouping (1,50,000), `dd-MM-yyyy` dates, amounts in words using lakh/crore. English UI only.

**NFR-13 — Monetary precision.** All money stored to 2 decimal places using exact decimal arithmetic. Floating-point types shall not be used for money anywhere.

**NFR-14 — Usability.** The billing screen shall be operable entirely from the keyboard, since it is the screen used under time pressure with a customer waiting.

**NFR-15 — Backup verification.** After each upload, the system shall verify that the uploaded file's size and checksum match what was produced locally.

---

## 5. Technical direction

Recommendations, with the reasoning stated so they can be argued with.

**Application shape — Spring Boot + JavaFX in a single process.**
The Spring context owns services, repositories, transactions and scheduling; JavaFX owns the UI, with controllers injected as Spring beans. One process, one executable, nothing for the owner to start or keep running. Spring's `@Scheduled` and `@Transactional` do real work here — the backup scheduler and the invoice-plus-stock atomicity respectively.

**Database — embedded SQLite** (Xerial JDBC driver), schema managed by **Flyway**.
The entire database is one file, which is exactly what makes FR-BAK-03 and FR-BAK-13 simple and reliable. SQLite's online backup API satisfies FR-BAK-04 directly. At single-user scale, its limitations do not apply. *Alternative:* H2 in embedded mode — closer to a conventional SQL server and better JDBC type fidelity, at the cost of a database that is harder to inspect with third-party tools.

**Money** — `BigDecimal` in Java, integer paisa or `NUMERIC` in the database. Never `double`.

**PDF generation** — a JVM PDF library driven by a template; the layout is fixed by FR-DOC-01 and needs no design tooling.

**Packaging** — `jpackage` producing an MSI or EXE with a bundled Java runtime, so the shop PC needs no Java installed and no runtime upgrade ever breaks the app.

**Backup pipeline**, end to end:
```
SQLite online backup → temp snapshot
  + Photos/ + Invoices/ + manifest.json
  → ZIP
  → AES-256-GCM  (key = Argon2id(backup password, random salt))
  → upload to the active cloud destination (Google Drive via Drive API v3, scope drive.file;
    or OneDrive via Microsoft Graph, scope Files.ReadWrite.AppFolder)
  → verify size + checksum
  → prune to 14 daily / 12 weekly
  → record outcome in backup_history (including which destination), surface on dashboard
```

**Google setup you must do once, before Google Drive can work:**
1. Create a project in Google Cloud Console.
2. Enable the Google Drive API.
3. Configure the OAuth consent screen (External, in Testing mode with your own account added as a test user is sufficient for a single shop).
4. Create an **OAuth client ID of type Desktop app**.
5. Put the resulting client ID and secret into the application's configuration.

This is a prerequisite, not a feature — the software cannot back up to Drive without it. A desktop app's client secret is not truly secret; the `drive.file` scope is what limits the damage, since it grants access only to files the app itself created.

**OneDrive setup *(added M12)* — nothing the shop owner does.** Unlike Google, Microsoft treats desktop apps as *public clients*: no client secret exists to protect, so this application ships with a single Azure app registration built in (created once by the project, not per shop). Connecting OneDrive is just "Connect" → sign in with a Microsoft account → consent — no project, no console, nothing pasted in. This is the practical advantage OneDrive has over Google Drive for a shop owner with no technical background, at the cost of a smaller free tier (5 GB vs. Drive's 15 GB).

**Token storage** — refresh token encrypted with Windows DPAPI, scoped to the current Windows user, for whichever destination is connected.

---

## 6. Constraints and assumptions

Each traced to the decision it came from. No item here was assumed.

| # | Constraint | Source |
|---|---|---|
| C-01 | Single Windows PC, single user, no server, no LAN | Stated: one PC, one user |
| C-02 | Retail only — no manufacturing, no BOM, no wholesale | Stated: retail showroom |
| C-03 | Every piece tracked individually, not by quantity | Stated: track each piece individually |
| C-15 | The item catalogue's field set is owner-defined, not fixed to any one trade (furniture, jewellery, electronics, ...) | Revised M15 — generalized from a furniture-only catalogue |
| C-04 | Indian GST applies **when the shop is GST-registered**; a shop that isn't can turn GST off entirely (FR-SYS-05) and bills plain, with no GSTIN, HSN or tax anywhere | Stated: India — GST; revised M10 — not every furniture retailer at this scale is GST-registered |
| C-05 | GST scope, when GST is on, stops at compliant invoices and period summaries | Stated: invoice + basic reports |
| C-06 | Both customer receivables and supplier payables tracked | Stated: advance + balance + supplier dues |
| C-07 | No barcode, label or thermal hardware | Stated: no hardware |
| C-08 | Invoice delivered as PDF via WhatsApp link and email | Stated: WhatsApp link + email |
| C-09 | Backups encrypted, retained daily+weekly, restorable in app, manual trigger available, to a choice of Google Drive or OneDrive | Stated: backup policy answers; OneDrive added M12 |
| C-10 | Java — Spring Boot + JavaFX | Stated: Java, Spring Boot + JavaFX |
| C-11 | Password login with idle auto-lock | Stated: login + auto-lock |
| C-12 | No import from Excel or Tally; opening stock keyed in | Stated: start fresh |
| C-13 | Warranty tracking excluded | Not selected among item fields |
| C-14 | CRM, follow-up and delivery tracking deferred | Stated: optional / future |
| A-01 | The shop PC has internet at least intermittently, and the active cloud destination (Google Drive, 15 GB free, or OneDrive, 5 GB free) has room for ~26 archives | Implied by choosing cloud backup — flag if untrue |
| A-02 | The shop is a **regular** GST dealer, not composition | **Unconfirmed — see §7.1** |
| A-03 | Piece cost basis excludes GST because input credit is claimable | **Unconfirmed — see §7.4** |

---

## 7. Open points requiring your decision

These are the only places where the specification is not settled. Each is flagged where it appears above.

**7.1 — GST registration type.**
This document assumes a **regular** GST registration, where invoices are titled "TAX INVOICE" and show CGST/SGST/IGST. If the shop is registered under the **composition scheme**, the document must instead be a "BILL OF SUPPLY" with no tax charged and a mandatory declaration, and no input credit is claimable — which also changes 7.4. Please confirm which applies.

*Partially resolved, M10:* this question is now moot for a shop that isn't GST-registered at all — FR-SYS-05 lets GST be turned off, in which case neither "TAX INVOICE" nor "BILL OF SUPPLY" applies; every document just says "INVOICE". For a shop that **is** GST-registered, Regular vs Composition is still exactly the open question above, and still needs an answer — the setup wizard and Settings both still ask for it whenever GST is on.

**7.2 — GST rates.**
The system stores a GST rate per item model and hard-codes nothing. Wooden furniture generally falls under HSN 9403 at 18%, but rates and HSN classification are your CA's call, not this document's. Confirm the rates to pre-load.

**7.3 — Login password recovery.**
With all data local and no cloud account, a forgotten login password has no automatic reset. Proposed mechanism: at first-run setup the system generates a one-time **recovery code**, which the owner writes down and stores outside the PC; entering it at the login screen allows setting a new password. It resets only the login password — it cannot decrypt backups, which stay tied to the backup password. Confirm this approach, or state a preferred one.

**7.4 — Cost basis for profit.**
Landed cost currently excludes GST paid on purchase, because a regular dealer claims that as input tax credit, so it is not a cost. If the shop cannot claim credit (composition scheme, or unregistered suppliers), cost should **include** GST and every profit figure changes. Depends on 7.1.

**7.5 — GST summary report.**
Your GST scope answer included period summaries for your CA, but the GST summary was not among the three reports you prioritised for v1. It is currently scheduled as **v1.1** (`04-roadmap.md`). Say the word and it moves into v1 — the underlying data is already captured either way, so this is purely about when the screen gets built.

---

## 8. Acceptance criteria — the tests that decide "done"

v1 is complete when all of the following pass on a clean install. **Verified by** names the
automated test that exercises it, added during milestone M9's acceptance run
(`docs/04-roadmap.md` M9); "Manual" marks the few items that genuinely need a human at a
real screen or a live external account — the same limitation already accepted for SMTP
(M6) and Google OAuth (M8) — and names exactly what remains to check by hand.

| # | Test | Verified by |
|---|---|---|
| A1 | Fresh install runs the setup wizard; the app is unusable until it completes | `SrsAcceptanceTest.a1_*` (gating logic); wizard screen flow itself: Manual |
| A2 | Wrong login password is refused; correct one enters; leaving the app idle locks it, and unlocking preserves an in-progress bill | `SrsAcceptanceTest.a2_*` (accept/reject); idle-lock state preservation: Manual |
| A3 | Opening stock of 6 chairs creates 6 individually tagged, individually costed, independently sellable pieces | `M2CatalogueAndPiecesTest` |
| A4 | A purchase bill for 1 sofa + 6 chairs with ₹2,000 freight creates 7 pieces whose landed costs sum exactly to taxable value + freight | `M3PurchasesTest` |
| A5 | The same-state sale of 2 chairs at ₹10,000 shows CGST ₹900 + SGST ₹900; changing place of supply to another state shows IGST ₹1,800 | `M4SalesTest` |
| A6 | Taking a ₹5,000 advance on a ₹21,800 invoice leaves ₹16,800 outstanding, visible in the dues report and aged correctly | `SrsAcceptanceTest.a6_*` |
| A7 | Those 2 chairs move to `SOLD`, disappear from available stock, and cannot be billed again | `M4SalesTest` |
| A8 | Invoice profit equals sale value minus those two specific pieces' landed costs, and does not change when the model's default price is later edited | `SrsAcceptanceTest.a8_*` |
| A9 | The invoice PDF carries every field listed in FR-DOC-01, with the total in Indian words. With GST off (FR-SYS-05), the same PDF instead carries no GSTIN, HSN, CGST or place of supply, while still carrying the item, total and amount-in-words | `SrsAcceptanceTest.a9_*` (GST-on: extracted PDF text checked for GSTIN/invoice no./customer/HSN/amount-in-words); `M10GstOptionalTest.invoicePdfHasNoGstContentWhenGstIsOff` (GST-off: same extraction, asserts the GST fields are absent and the non-GST ones survive); full visual layout: Manual |
| A10 | WhatsApp opens with the customer's number and the message pre-filled; email delivers the PDF as an attachment | Manual — opens the OS WhatsApp handler / needs a live SMTP send, same limitation as M6 |
| A11 | A sales return restores the pieces to `IN_STOCK`, issues a credit note, and adjusts the balance | `M4SalesTest` |
| A12 | Cancelling an invoice restores stock, reverses payments, retains the record marked cancelled, and never reuses the number | `M4SalesTest` |
| A13 | Abandoning a half-entered bill consumes no invoice number; saved invoices are strictly consecutive | `M4SalesTest` |
| A14 | A scheduled backup produces an encrypted archive in Drive; the wrong password fails to decrypt it | `M8BackupTest` (archive + wrong-password rejection); live Drive upload: Manual, needs a real Google account |
| A15 | Turning the PC off through the scheduled time results in a catch-up backup after the next start | `M8BackupTest` |
| A16 | Retention prunes to 14 daily and 12 weekly archives | `SrsAcceptanceTest.a16_*` (the real default counts, not just an overridden small one) |
| A17 | Restoring an archive onto a clean install reproduces all data **including photos and invoice PDFs** | `SrsAcceptanceTest.a17_*` — flagged in `docs/04-roadmap.md` as one of the two tests most likely to fail late; deletes a photo and a PDF from disk, restores, confirms both come back byte-for-byte |
| A18 | Disconnecting the internet leaves every function working except upload, email and share; the archive is produced locally and uploads when connectivity returns | `M8BackupTest` (no Drive configured is functionally the same code path as no connectivity — both land in `UPLOAD_PENDING`); a real network disconnect: Manual |
| A19 | No backup for 48 hours produces a visible, recurring warning | `SrsAcceptanceTest.a19_*` (loads the real dashboard FXML, confirms the status tile turns red) |
| A20 | Reports over 20,000 pieces and 20,000 invoices return within the NFR-04 targets | `M9PerformanceTest` — this is the test that caught and fixed a real N+1 query pattern in the dues-aging report (customer dues aging: 180s → 0.2s) |
