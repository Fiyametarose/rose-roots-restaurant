CREATE TABLE staff_account (
    id uuid PRIMARY KEY,
    username varchar(48) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,47}$'),
    staff_role text NOT NULL CHECK (staff_role IN ('CHEF', 'SUPERVISOR', 'MANAGER')),
    password_hash text,
    credential_id uuid UNIQUE REFERENCES ordering_credential(id),
    enabled boolean NOT NULL DEFAULT true,
    allergy_reviewer boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    CHECK ((staff_role = 'MANAGER') = (password_hash IS NOT NULL)),
    CHECK ((staff_role = 'MANAGER') = (credential_id IS NULL)),
    CHECK (NOT allergy_reviewer OR staff_role = 'CHEF')
);

CREATE TABLE staff_account_audit (
    account_id uuid NOT NULL REFERENCES staff_account(id),
    version bigint NOT NULL,
    manager_username text NOT NULL,
    staff_role text NOT NULL,
    enabled boolean NOT NULL,
    allergy_reviewer boolean NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, version)
);
