-- One restaurant per deployment, matching the existing shared catalog.
CREATE TABLE ordering_restaurant (
    id uuid PRIMARY KEY,
    singleton boolean NOT NULL DEFAULT true UNIQUE CHECK (singleton)
);

CREATE TABLE ordering_table (
    id uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL REFERENCES ordering_restaurant(id),
    display_number varchar(24) NOT NULL CHECK (btrim(display_number) <> ''),
    UNIQUE (restaurant_id, display_number),
    UNIQUE (restaurant_id, id)
);

CREATE TABLE ordering_terminal (
    id uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL REFERENCES ordering_restaurant(id),
    table_id uuid,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    FOREIGN KEY (restaurant_id, table_id) REFERENCES ordering_table(restaurant_id, id),
    UNIQUE (restaurant_id, id)
);

CREATE TABLE ordering_credential (
    id uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL REFERENCES ordering_restaurant(id),
    terminal_id uuid,
    scope text NOT NULL CHECK (scope IN ('TERMINAL', 'KITCHEN')),
    token_sha256 bytea NOT NULL UNIQUE CHECK (octet_length(token_sha256) = 32),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    FOREIGN KEY (restaurant_id, terminal_id) REFERENCES ordering_terminal(restaurant_id, id),
    CHECK ((scope = 'TERMINAL') = (terminal_id IS NOT NULL))
);

CREATE TABLE dining_session (
    id uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL,
    terminal_id uuid NOT NULL,
    mode text NOT NULL CHECK (mode IN ('DINE_IN', 'TAKEAWAY')),
    table_id uuid,
    table_number varchar(24),
    pickup_reference varchar(40) UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    closed_at timestamptz,
    status text NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'EXPIRED')),
    FOREIGN KEY (restaurant_id, terminal_id) REFERENCES ordering_terminal(restaurant_id, id),
    FOREIGN KEY (restaurant_id, table_id) REFERENCES ordering_table(restaurant_id, id),
    CHECK ((mode = 'DINE_IN' AND table_id IS NOT NULL AND table_number IS NOT NULL AND pickup_reference IS NULL)
        OR (mode = 'TAKEAWAY' AND table_id IS NULL AND table_number IS NULL AND pickup_reference IS NOT NULL))
);
CREATE UNIQUE INDEX dining_session_one_open ON dining_session (terminal_id) WHERE status = 'OPEN';

CREATE TABLE ordering_quote (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES dining_session(id),
    expires_at timestamptz NOT NULL,
    draft jsonb NOT NULL,
    pricing jsonb NOT NULL,
    UNIQUE (session_id, id)
);

CREATE TABLE accepted_order (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    session_id uuid NOT NULL REFERENCES dining_session(id),
    quote_id uuid NOT NULL UNIQUE,
    accepted_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('RECEIVED', 'PREPARING', 'READY')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    visit_allergy jsonb NOT NULL,
    subtotal_minor bigint NOT NULL CHECK (subtotal_minor BETWEEN 0 AND 9007199254740991),
    delay_at timestamptz NOT NULL,
    FOREIGN KEY (session_id, quote_id) REFERENCES ordering_quote(session_id, id)
);
CREATE INDEX accepted_order_session ON accepted_order (session_id, sequence);

CREATE TABLE accepted_order_item (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES accepted_order(id),
    menu_item_id uuid NOT NULL REFERENCES catalog_item(id),
    position integer NOT NULL CHECK (position >= 0),
    pricing jsonb NOT NULL,
    allergy jsonb NOT NULL,
    instructions varchar(240) NOT NULL,
    allergy_risk jsonb NOT NULL,
    allergy_review_status text NOT NULL CHECK (allergy_review_status IN
        ('NOT_REQUIRED', 'PENDING_ALLERGY_REVIEW', 'APPROVED_FOR_PREPARATION')),
    preparation_status text NOT NULL CHECK (preparation_status IN ('RECEIVED', 'PREPARING', 'READY')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_by uuid REFERENCES ordering_credential(id),
    UNIQUE (order_id, position)
);

CREATE TABLE ordering_submission (
    session_id uuid NOT NULL REFERENCES dining_session(id),
    idempotency_key uuid NOT NULL,
    request_fingerprint bytea NOT NULL CHECK (octet_length(request_fingerprint) = 32),
    order_id uuid NOT NULL UNIQUE REFERENCES accepted_order(id),
    accepted_response jsonb NOT NULL,
    PRIMARY KEY (session_id, idempotency_key)
);

CREATE TABLE ordering_outbox (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES accepted_order(id),
    order_version bigint NOT NULL,
    event_type text NOT NULL CHECK (event_type IN ('ORDER_CREATED', 'ORDER_UPDATED')),
    occurred_at timestamptz NOT NULL,
    UNIQUE (order_id, order_version)
);
