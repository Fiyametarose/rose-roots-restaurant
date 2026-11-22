ALTER TABLE staff_account ADD COLUMN archived_at timestamptz;
ALTER TABLE staff_account ADD COLUMN password_reset_required boolean NOT NULL DEFAULT false;
ALTER TABLE staff_account ADD COLUMN security_version bigint NOT NULL DEFAULT 1 CHECK (security_version > 0);
ALTER TABLE staff_account ADD CHECK (archived_at IS NULL OR NOT enabled);

UPDATE staff_account SET password_reset_required = true, available_until = NULL WHERE NOT enabled;
UPDATE ordering_credential c SET revoked_at = COALESCE(c.revoked_at, now())
    FROM staff_account s WHERE s.credential_id = c.id AND NOT s.enabled;
DELETE FROM kitchen_staff_session k USING staff_account s WHERE k.staff_id = s.id AND NOT s.enabled;

ALTER TABLE staff_account DROP COLUMN allergy_reviewer;
ALTER TABLE staff_account_audit ADD COLUMN previous_state text
    CHECK (previous_state IN ('ACTIVE', 'DEACTIVATED', 'ARCHIVED'));
ALTER TABLE staff_account_audit ADD COLUMN new_state text
    CHECK (new_state IN ('ACTIVE', 'DEACTIVATED', 'ARCHIVED'));
