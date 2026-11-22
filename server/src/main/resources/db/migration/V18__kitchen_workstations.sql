ALTER TABLE ordering_credential DROP CONSTRAINT ordering_credential_scope_check;
ALTER TABLE ordering_credential ADD CONSTRAINT ordering_credential_scope_check
    CHECK (scope IN ('TERMINAL', 'KITCHEN', 'KITCHEN_WORKSTATION'));
ALTER TABLE ordering_credential ADD CONSTRAINT ordering_credential_id_scope_key UNIQUE (id, scope);

CREATE TABLE kitchen_workstation (
    credential_id uuid PRIMARY KEY,
    scope text NOT NULL DEFAULT 'KITCHEN_WORKSTATION' CHECK (scope = 'KITCHEN_WORKSTATION'),
    label varchar(64) NOT NULL CHECK (btrim(label) <> ''),
    issued_by text NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT now(),
    revoked_by text,
    FOREIGN KEY (credential_id, scope) REFERENCES ordering_credential(id, scope)
);
