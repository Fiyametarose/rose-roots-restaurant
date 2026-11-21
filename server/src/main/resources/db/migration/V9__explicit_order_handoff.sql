ALTER TABLE accepted_order DROP CONSTRAINT accepted_order_status_check;
ALTER TABLE accepted_order ADD CONSTRAINT accepted_order_status_check
    CHECK (status IN ('RECEIVED', 'PREPARING', 'READY', 'CANCELLED', 'SERVED', 'COLLECTED'));
ALTER TABLE accepted_order_item DROP CONSTRAINT accepted_order_item_preparation_status_check;
ALTER TABLE accepted_order_item ADD CONSTRAINT accepted_order_item_preparation_status_check
    CHECK (preparation_status IN ('RECEIVED', 'PREPARING', 'READY', 'CANCELLED', 'SERVED', 'COLLECTED'));

CREATE TABLE order_handoff (
    order_id uuid PRIMARY KEY REFERENCES accepted_order(id),
    staff_credential_id uuid NOT NULL REFERENCES ordering_credential(id),
    status text NOT NULL CHECK (status IN ('SERVED', 'COLLECTED')),
    previous_version bigint NOT NULL CHECK (previous_version > 0),
    version bigint NOT NULL CHECK (version = previous_version + 1),
    completed_at timestamptz NOT NULL
);
