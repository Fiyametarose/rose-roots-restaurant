ALTER TABLE staff_account ADD COLUMN shift_started_at timestamptz;
CREATE TABLE staff_duty_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    staff_id uuid NOT NULL REFERENCES staff_account(id),
    actor text NOT NULL,
    action text NOT NULL CHECK (action IN ('START','END','EMERGENCY_END')),
    version bigint NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    shift_ends_at timestamptz,
    UNIQUE(staff_id,version)
);
CREATE TABLE waiter_table_assignment (
    staff_id uuid NOT NULL REFERENCES staff_account(id),
    table_id uuid NOT NULL REFERENCES ordering_table(id),
    PRIMARY KEY(staff_id,table_id)
);
ALTER TABLE assistance_request ADD COLUMN owner_staff_id uuid REFERENCES staff_account(id);
ALTER TABLE assistance_transition ADD COLUMN actor_staff_id uuid REFERENCES staff_account(id);
CREATE TABLE assistance_reassignment (
    request_id uuid NOT NULL REFERENCES assistance_request(id),
    version bigint NOT NULL,
    supervisor_id uuid NOT NULL REFERENCES staff_account(id),
    previous_waiter_id uuid REFERENCES staff_account(id),
    waiter_id uuid NOT NULL REFERENCES staff_account(id),
    reason text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(request_id,version)
);
ALTER TABLE kitchen_configuration_audit DROP CONSTRAINT kitchen_configuration_audit_entity_check;
ALTER TABLE kitchen_configuration_audit ADD CHECK (entity IN ('STATION','CHEF_AVAILABILITY','PREP_PROFILE','WAITER_ASSIGNMENT'));
ALTER TABLE assistance_request DROP CONSTRAINT assistance_request_version_check;
ALTER TABLE assistance_request DROP CONSTRAINT assistance_request_check2;
ALTER TABLE assistance_request ADD CHECK (version >= CASE status
    WHEN 'SUBMITTED' THEN 1 WHEN 'ACKNOWLEDGED' THEN 2 WHEN 'IN_PROGRESS' THEN 3 ELSE 4 END);
ALTER TABLE assistance_transition DROP CONSTRAINT assistance_transition_check;
ALTER TABLE assistance_transition ADD CHECK (version >= 2 AND (from_status,to_status) IN
    (('SUBMITTED','ACKNOWLEDGED'),('ACKNOWLEDGED','IN_PROGRESS'),('IN_PROGRESS','COMPLETED')));
