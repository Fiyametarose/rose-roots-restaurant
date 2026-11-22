ALTER TABLE accepted_order ADD COLUMN rush boolean NOT NULL DEFAULT false;
ALTER TABLE accepted_order ADD COLUMN delayed_override boolean;

CREATE TABLE order_priority_audit (
    order_id uuid NOT NULL REFERENCES accepted_order(id),
    version bigint NOT NULL,
    marker text NOT NULL CHECK (marker IN ('RUSH', 'DELAYED')),
    enabled boolean NOT NULL,
    reason varchar(240) NOT NULL CHECK (btrim(reason) <> ''),
    staff_credential_id uuid REFERENCES ordering_credential(id),
    manager_username text,
    changed_at timestamptz NOT NULL,
    PRIMARY KEY (order_id, version),
    CHECK ((staff_credential_id IS NOT NULL) <> (manager_username IS NOT NULL))
);
