CREATE TABLE kitchen_station (
    id uuid PRIMARY KEY,
    code text NOT NULL UNIQUE CHECK (code IN ('HOT', 'COLD', 'BEVERAGES')),
    name text NOT NULL CHECK (btrim(name) <> ''),
    active boolean NOT NULL DEFAULT true,
    parallel_slots integer NOT NULL DEFAULT 0 CHECK (parallel_slots BETWEEN 0 AND 1000),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0)
);

INSERT INTO kitchen_station (id, code, name) VALUES
    ('0f2f27d8-1680-4e72-92a6-e73e5f08bb11', 'HOT', 'Hot Kitchen'),
    ('0f2f27d8-1680-4e72-92a6-e73e5f08bb12', 'COLD', 'Cold Preparation'),
    ('0f2f27d8-1680-4e72-92a6-e73e5f08bb13', 'BEVERAGES', 'Beverages');

ALTER TABLE staff_account ADD COLUMN available_until timestamptz;

CREATE TABLE kitchen_station_chef (
    station_id uuid NOT NULL REFERENCES kitchen_station(id),
    chef_id uuid NOT NULL REFERENCES staff_account(id),
    PRIMARY KEY (station_id, chef_id)
);

CREATE TABLE kitchen_prep_profile (
    menu_item_id uuid PRIMARY KEY REFERENCES catalog_item(id),
    station_id uuid NOT NULL REFERENCES kitchen_station(id),
    duration_seconds integer NOT NULL CHECK (duration_seconds BETWEEN 1 AND 86400),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0)
);

ALTER TABLE accepted_order_item ADD COLUMN station_id uuid REFERENCES kitchen_station(id);
ALTER TABLE accepted_order_item ADD COLUMN planned_duration_seconds integer CHECK (planned_duration_seconds BETWEEN 1 AND 86400);
ALTER TABLE accepted_order_item ADD COLUMN preparation_started_at timestamptz;
ALTER TABLE accepted_order_item ADD CHECK ((station_id IS NULL) = (planned_duration_seconds IS NULL));

CREATE TABLE kitchen_configuration_audit (
    entity text NOT NULL CHECK (entity IN ('STATION', 'CHEF_AVAILABILITY', 'PREP_PROFILE')),
    entity_id uuid NOT NULL,
    version bigint NOT NULL,
    manager_username text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (entity, entity_id, version)
);
