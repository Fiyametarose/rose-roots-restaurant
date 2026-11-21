ALTER TABLE catalog_item
    ADD COLUMN customer_source_id varchar(100) UNIQUE,
    ADD COLUMN source_details jsonb,
    ALTER COLUMN serving_size DROP NOT NULL,
    ALTER COLUMN kitchen_station DROP NOT NULL,
    ALTER COLUMN prep_minutes DROP NOT NULL;

ALTER TABLE catalog_menu_image
    ADD COLUMN source_sha256 varchar(64) UNIQUE;
