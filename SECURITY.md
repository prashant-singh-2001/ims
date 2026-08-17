# Security Policy

## Supported versions

This project has not yet cut a tagged release (the working version is
`0.1.0-SNAPSHOT`). Security fixes are applied to the `main` branch only —
there is no older release line to backport to.

## What this application handles

This is a single-PC, single-user desktop application for a furniture retail
shop. It is designed to work entirely offline except for three explicit
network operations: Google Drive backup upload, SMTP email, and opening a
WhatsApp deep link. Data it handles includes:

- Business records: stock, purchases, GST sales invoices, customer and
  supplier balances.
- The owner's login password (hashed with bcrypt, never stored or logged in
  plain text).
- A backup encryption password and a Google Drive OAuth refresh token, both
  protected at rest with Windows DPAPI (recoverable only by the same Windows
  user on the same PC).
- Backup archives (database, photos, invoice PDFs) encrypted with
  AES-256-GCM before they ever leave the machine.

Nothing in this repository's source code contains real credentials, API keys,
or shop data — SMTP credentials, the Google OAuth client ID/secret, and the
backup password are all entered at runtime through the application's own
Settings screen and stored locally, never committed.

## Reporting a vulnerability

**Please do not open a public GitHub issue for a security vulnerability.**

Preferred: use GitHub's private vulnerability reporting for this repository,
if enabled (repository **Security** tab → **Report a vulnerability**).

If that option isn't available, contact the maintainer directly —
[@prashant-singh-2001](https://github.com/prashant-singh-2001) — rather than
filing a public issue, so a fix can be prepared before the details are public.

Please include:

- A description of the vulnerability and its potential impact.
- Steps to reproduce it, or a proof of concept.
- The application version and Windows version, if relevant.

## Response expectations

This is currently a personal project maintained by one person, not a
funded or staffed security program. Reports will be acknowledged and
investigated on a best-effort basis — there is no guaranteed response time,
but genuine reports will not be ignored.
