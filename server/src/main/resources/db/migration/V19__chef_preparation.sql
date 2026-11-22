ALTER TABLE staff_account ADD COLUMN kitchen_password_hash text;
ALTER TABLE accepted_order_item ADD COLUMN claimed_by uuid REFERENCES staff_account(id);

CREATE TABLE kitchen_staff_session (
    token_sha256 bytea PRIMARY KEY CHECK (octet_length(token_sha256) = 32),
    staff_id uuid NOT NULL REFERENCES staff_account(id),
    entry_credential_id uuid NOT NULL REFERENCES ordering_credential(id),
    expires_at timestamptz NOT NULL
);
CREATE INDEX kitchen_staff_session_staff ON kitchen_staff_session(staff_id);
CREATE TABLE kitchen_sign_in_limit (
    entry_credential_id uuid PRIMARY KEY REFERENCES ordering_credential(id),
    minute timestamptz NOT NULL,
    attempts integer NOT NULL
);

CREATE TABLE item_preparation_history (
    item_id uuid NOT NULL REFERENCES accepted_order_item(id),
    order_id uuid NOT NULL REFERENCES accepted_order(id),
    item_version bigint NOT NULL CHECK (item_version > 0),
    action text NOT NULL CHECK (action IN ('CLAIMED','STARTED','READY','RELEASED','REASSIGNED','HANDOFF')),
    actor_staff_id uuid REFERENCES staff_account(id),
    actor_credential_id uuid NOT NULL REFERENCES ordering_credential(id),
    previous_chef_id uuid REFERENCES staff_account(id),
    next_chef_id uuid REFERENCES staff_account(id),
    previous_status text NOT NULL,
    next_status text NOT NULL,
    reason text,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (item_id, item_version)
);
CREATE INDEX item_preparation_history_order ON item_preparation_history(order_id);
