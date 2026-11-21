CREATE TABLE catalog_write_lock (
    id integer PRIMARY KEY CHECK (id = 1)
);
INSERT INTO catalog_write_lock VALUES (1);

CREATE TABLE catalog_category (
    id uuid PRIMARY KEY,
    parent_id uuid REFERENCES catalog_category(id),
    name varchar(100) NOT NULL CHECK (btrim(name) <> ''),
    display_order integer NOT NULL CHECK (display_order >= 0),
    status varchar(12) NOT NULL CHECK (status IN ('VISIBLE', 'HIDDEN', 'ARCHIVED')),
    CHECK (id <> parent_id)
);
CREATE UNIQUE INDEX catalog_category_root_name ON catalog_category (lower(name)) WHERE parent_id IS NULL;
CREATE UNIQUE INDEX catalog_category_child_name ON catalog_category (parent_id, lower(name)) WHERE parent_id IS NOT NULL;

CREATE TABLE catalog_item (
    id uuid PRIMARY KEY,
    category_id uuid NOT NULL REFERENCES catalog_category(id),
    name varchar(120) NOT NULL CHECK (btrim(name) <> ''),
    description varchar(4000) NOT NULL CHECK (btrim(description) <> ''),
    cuisine varchar(20) NOT NULL CHECK (cuisine IN ('ETHIOPIAN', 'SOUTH_SUDANESE', 'INTERNATIONAL')),
    price numeric(12, 2) NOT NULL CHECK (price >= 0),
    serving_size varchar(100) NOT NULL CHECK (btrim(serving_size) <> ''),
    ingredients jsonb NOT NULL,
    allergens jsonb NOT NULL,
    dietary_tags jsonb NOT NULL,
    prep_minutes integer NOT NULL CHECK (prep_minutes BETWEEN 1 AND 1440),
    kitchen_station varchar(80) NOT NULL CHECK (btrim(kitchen_station) <> ''),
    availability varchar(12) NOT NULL CHECK (availability IN ('AVAILABLE', 'SOLD_OUT')),
    archived boolean NOT NULL,
    customization_groups jsonb NOT NULL,
    image_reference varchar(1024),
    display_order integer NOT NULL CHECK (display_order >= 0)
);
CREATE INDEX catalog_item_category ON catalog_item (category_id, display_order);

CREATE TABLE catalog_item_audit (
    id uuid PRIMARY KEY,
    item_id uuid NOT NULL REFERENCES catalog_item(id),
    actor varchar(255) NOT NULL,
    changed_at timestamptz NOT NULL,
    previous_price numeric(12, 2) NOT NULL,
    price numeric(12, 2) NOT NULL,
    previous_availability varchar(12) NOT NULL,
    availability varchar(12) NOT NULL,
    previously_archived boolean NOT NULL,
    archived boolean NOT NULL,
    reason varchar(500)
);
CREATE INDEX catalog_item_audit_history ON catalog_item_audit (item_id, changed_at);
