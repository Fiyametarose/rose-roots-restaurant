ALTER TABLE dining_session ADD COLUMN bill_version bigint NOT NULL DEFAULT 1 CHECK (bill_version > 0);

CREATE TABLE payment_attempt (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES dining_session(id),
    idempotency_key uuid NOT NULL,
    method text NOT NULL CHECK (method IN ('CASH', 'MPESA', 'CARD')),
    status text NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    bill_version bigint NOT NULL CHECK (bill_version > 0),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    currency char(3) NOT NULL CHECK (currency = 'KES'),
    snapshot jsonb NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    cancellation_requested boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (session_id, idempotency_key),
    UNIQUE (session_id, id)
);

CREATE UNIQUE INDEX payment_one_pending ON payment_attempt(session_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX payment_one_settlement ON payment_attempt(session_id) WHERE status = 'SUCCEEDED';

CREATE TABLE payment_receipt (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES dining_session(id),
    payment_id uuid NOT NULL UNIQUE,
    verified_at timestamptz NOT NULL,
    receipt jsonb NOT NULL,
    FOREIGN KEY (session_id, payment_id) REFERENCES payment_attempt(session_id, id)
);

CREATE FUNCTION preserve_payment_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'An issued payment receipt is immutable';
END;
$$;

CREATE TRIGGER payment_receipt_immutable BEFORE UPDATE OR DELETE ON payment_receipt
    FOR EACH ROW EXECUTE FUNCTION preserve_payment_receipt();

CREATE TABLE payment_audit (
    payment_id uuid NOT NULL REFERENCES payment_attempt(id),
    version bigint NOT NULL,
    event text NOT NULL CHECK (event IN ('STARTED','CANCELLATION_REQUESTED','CASH_CONFIRMED','CASH_FAILED','CASH_CANCELLED')),
    terminal_credential_id uuid REFERENCES ordering_credential(id),
    manager_username text,
    reason varchar(240),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_id, version),
    CHECK ((terminal_credential_id IS NOT NULL) <> (manager_username IS NOT NULL))
);

CREATE TABLE dining_session_closure (
    session_id uuid PRIMARY KEY REFERENCES dining_session(id),
    manager_username text NOT NULL,
    bill_version bigint NOT NULL,
    closed_at timestamptz NOT NULL
);
