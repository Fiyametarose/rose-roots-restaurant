CREATE TABLE assistance_request (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    session_id uuid NOT NULL REFERENCES dining_session(id),
    submission_key uuid NOT NULL,
    input_sha256 bytea NOT NULL CHECK (octet_length(input_sha256) = 32),
    type text NOT NULL CHECK (type IN ('WAITER','DRINKING_WATER','CUTLERY','NAPKINS','ORDER_HELP','BILL','OTHER')),
    note varchar(240) NOT NULL CHECK (type = 'OTHER' OR note = ''),
    status text NOT NULL CHECK (status IN ('SUBMITTED','ACKNOWLEDGED','IN_PROGRESS','COMPLETED')),
    version bigint NOT NULL DEFAULT 1 CHECK (version BETWEEN 1 AND 4),
    submitted_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL CHECK (updated_at >= submitted_at),
    UNIQUE (session_id, submission_key),
    CHECK ((status, version) IN (('SUBMITTED',1),('ACKNOWLEDGED',2),('IN_PROGRESS',3),('COMPLETED',4)))
);
CREATE INDEX assistance_request_history ON assistance_request (session_id, sequence);

CREATE TABLE assistance_transition (
    request_id uuid NOT NULL REFERENCES assistance_request(id),
    staff_credential_id uuid NOT NULL REFERENCES ordering_credential(id),
    from_status text NOT NULL,
    to_status text NOT NULL,
    version bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (request_id, version),
    CHECK ((from_status, to_status, version) IN (('SUBMITTED','ACKNOWLEDGED',2),
        ('ACKNOWLEDGED','IN_PROGRESS',3),('IN_PROGRESS','COMPLETED',4)))
);
