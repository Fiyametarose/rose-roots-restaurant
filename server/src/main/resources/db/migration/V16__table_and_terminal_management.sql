ALTER TABLE ordering_table ADD COLUMN enabled boolean NOT NULL DEFAULT true;
ALTER TABLE ordering_table ADD COLUMN version bigint NOT NULL DEFAULT 1 CHECK (version > 0);
ALTER TABLE ordering_terminal ADD COLUMN enabled boolean NOT NULL DEFAULT true;
ALTER TABLE ordering_terminal ADD COLUMN label varchar(80) NOT NULL DEFAULT 'Terminal';

CREATE TABLE ordering_device_audit (
    resource text NOT NULL CHECK (resource IN ('TABLE', 'TERMINAL')),
    resource_id uuid NOT NULL,
    version bigint NOT NULL,
    manager_username text NOT NULL,
    change jsonb NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (resource, resource_id, version)
);
