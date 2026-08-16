-- FR-DOC-04 needs somewhere to email an invoice to - the customer table never captured
-- this (docs/02-data-model.md section 4.5 only specified phone, which drives the WhatsApp
-- share). Nullable: most retail customers still won't have one on file.
ALTER TABLE customer ADD COLUMN email TEXT;
