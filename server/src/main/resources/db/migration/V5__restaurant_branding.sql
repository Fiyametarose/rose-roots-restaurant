CREATE TABLE restaurant_logo (
    id uuid PRIMARY KEY,
    storage_reference varchar(40) NOT NULL UNIQUE,
    width integer NOT NULL CHECK (width BETWEEN 1 AND 1024),
    height integer NOT NULL CHECK (height BETWEEN 1 AND 1024),
    file_size bigint NOT NULL CHECK (file_size > 0),
    created_at timestamptz NOT NULL,
    created_by varchar(255) NOT NULL,
    CHECK (storage_reference = id::text || '.png')
);

CREATE TABLE restaurant_settings (
    id integer PRIMARY KEY CHECK (id = 1),
    version bigint NOT NULL DEFAULT 0,
    name varchar(120) NOT NULL CHECK (btrim(name) <> ''),
    tagline varchar(240),
    description varchar(1500),
    telephone varchar(40),
    email varchar(254),
    address varchar(500),
    theme varchar(24) NOT NULL CHECK (theme IN ('FOREST_GOLD', 'WINE_GOLD', 'SLATE_GOLD')),
    logo_id uuid REFERENCES restaurant_logo(id),
    default_logo boolean NOT NULL,
    CHECK (NOT default_logo OR logo_id IS NULL)
);

INSERT INTO restaurant_settings (id, name, tagline, theme, default_logo)
VALUES (1, 'Rose & Roots', 'Experience the rich flavors of Ethiopia, South Sudan and the world, at your table.', 'FOREST_GOLD', true);

CREATE TABLE restaurant_settings_audit (
    id uuid PRIMARY KEY,
    actor varchar(255) NOT NULL,
    changed_at timestamptz NOT NULL,
    before_settings jsonb NOT NULL,
    after_settings jsonb NOT NULL
);
