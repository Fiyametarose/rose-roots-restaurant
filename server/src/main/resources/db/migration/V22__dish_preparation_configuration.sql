ALTER TABLE catalog_item ADD COLUMN edit_version bigint NOT NULL DEFAULT 1 CHECK (edit_version > 0);
ALTER TABLE accepted_order ALTER COLUMN delay_at DROP NOT NULL;
