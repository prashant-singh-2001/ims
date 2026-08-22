# PieceTrack — Documentation

Windows desktop software for a retail shop tracking individually distinguishable stock: piece-level stock tracking, GST invoicing, customer and supplier balances, and encrypted daily/weekly backups to Google Drive or OneDrive.

For an overview, build instructions, and the tech stack, see the [repository root README](../README.md). This folder is the detailed specification the build was verified against.

**Status:** v1 (milestones M1–M9, see [04-roadmap.md](04-roadmap.md)) is built and verified, and six post-v1 milestones have since shipped on top of it — M10 (AtlantaFX restyle, persistent navigation shell, optional GST), M11 (per-piece condition photos), M12 (OneDrive as a second backup destination alongside Google Drive), M13 (Bookings tab tracking per-item delivery status), M14 (per-machine licensing, activation and a remote kill switch), and M15 (generalized the furniture-only catalogue into user-defined item attributes, renamed the product to PieceTrack). 118 automated tests, full SRS acceptance run. `v1.0.0` is the current tagged release — the first considered feature-complete and production-ready end to end (this is a semver milestone, not the same thing as this document's own "v1" scope label — see `04-roadmap.md` §1 for that distinction). Five requirements points from the original gathering pass are still open (below), one now partially resolved by M10; v1 proceeded on the documented defaults for each in the meantime.
**Last updated:** 22 August 2026

---

## The documents

| Document | What it is | Read it when |
|---|---|---|
| [01-requirements.md](01-requirements.md) | The SRS. Scope, actors, ~90 numbered requirements, non-functional requirements, technical direction, open points, and the 20 acceptance tests that define "done". | Deciding what gets built, and settling any argument about whether something is in scope. |
| [02-data-model.md](02-data-model.md) | Tables, columns, keys, indexes, the piece lifecycle state machine, and the exact landed-cost arithmetic. | Before writing the first migration, and whenever a schema question comes up. |
| [03-screens.md](03-screens.md) | Navigation map and every screen — fields, actions, validations, and the requirement IDs each satisfies. | Building the UI, and checking nothing was missed. |
| [04-roadmap.md](04-roadmap.md) | Build order in nine v1 milestones plus six post-v1 milestones (M10 restyle/GST-optional, M11 piece photos, M12 OneDrive backup, M13 Bookings/delivery tracking, M14 licensing/activation, M15 generic inventory/PieceTrack rename), indicative effort and risk, invariants no later phase may break, and what waits for v1.1, v2 and v3. | Planning the sequence of work. |

---

## What was decided

| | |
|---|---|
| Business | Any retail business tracking individually distinguishable stock — buys finished goods, sells to walk-in customers |
| Deployment | One Windows PC, one user, embedded database, works offline |
| Stock model | **Every physical piece tracked individually** — its own tag, its own cost, its own location |
| Modules | Purchases and goods receipt · GST sales invoicing · Customer and supplier payments |
| Tax | India GST — CGST/SGST/IGST, HSN, compliant tax invoices; no GSTR export, no e-invoice, no e-way bill |
| Documents | PDF invoices, shared by WhatsApp link and email |
| Reports | Stock on hand and valuation · Sales and profit · Outstanding dues, with aging throughout |
| Security | Password login with idle auto-lock |
| Backup | Daily + weekly, AES-256 encrypted, to Google Drive via the Drive API, with retention, in-app restore, manual trigger and visible status |
| Technology | Java — Spring Boot + JavaFX in one process, SQLite, packaged with `jpackage` |
| Deferred | Customer CRM, delivery tracking, multi-user, data import |

---

## Two things that shape everything else

**Piece-level tracking is the core design decision.** The shop does not count "4 chairs"; it tracks four specific chairs, each with the cost it actually arrived at. That is what makes profit per sale real instead of averaged — and it is why an invoice for six chairs is six rows in the database even though it prints as one line.

**The backup is the reason this can safely live on one PC.** Everything else in the system is replaceable; the shop's own records are not. So the backup covers the database *and* the photos *and* the generated PDFs, it is encrypted before it leaves the machine, it catches up when the PC was switched off, it queues when the internet is down, and it complains loudly and repeatedly when it has not succeeded for two days.

---

## Still open — five things need your answer

v1 was built on the documented default for each of these; none has blocked development, but each should still be confirmed. Listed in full in [SRS §7](01-requirements.md#7-open-points-requiring-your-decision):

1. **GST registration type** — regular (tax invoice) or composition (bill of supply)? Changes the invoice format and item 4 below. *Partially resolved, M10:* moot for a shop that isn't GST-registered at all (GST can now be turned off entirely) — still open for any shop that is.
2. **GST rates to pre-load** — furniture is generally HSN 9403 at 18%, but that is your CA's call, not this document's.
3. **Login password recovery** — proposed: a one-time recovery code issued at setup, kept off the PC.
4. **Cost basis** — landed cost currently excludes GST, on the basis that input credit is claimable. Depends on 1.
5. **GST summary report** — currently scheduled for v1.1; say the word and it moves into v1.

## Things you need to set up

Google Drive backup cannot work until a Google Cloud project exists with the Drive API enabled and a **Desktop app** OAuth client created. The five steps are listed in [SRS §5](01-requirements.md#5-technical-direction). This is a prerequisite for the software, not a feature of it. **OneDrive needs none of this** (M12) — the app carries its own OAuth app registration, so a shop can connect it with just a sign-in.

**Activation cannot work at all until the licence server is deployed** (M14) — unlike Google Drive, this one isn't optional: the setup wizard's final step refuses to finish without a successful activation. The licence server is a small Cloudflare Worker you deploy and operate yourself; the full runbook (generate the signing key pair, deploy, issue an activation key per shop, revoke one) is in [`license-server/README.md`](../license-server/README.md).
