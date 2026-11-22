ALTER TABLE staff_account DROP CONSTRAINT staff_account_staff_role_check;
ALTER TABLE staff_account ADD CONSTRAINT staff_account_staff_role_check
    CHECK (staff_role IN ('CHEF', 'SUPERVISOR', 'MANAGER', 'WAITER'));

ALTER TABLE order_handoff ADD COLUMN actor_staff_id uuid REFERENCES staff_account(id);
ALTER TABLE order_handoff ADD COLUMN actor_role text;
ALTER TABLE order_handoff ADD COLUMN previous_status text;
ALTER TABLE order_handoff ADD CONSTRAINT order_handoff_personal_actor_check CHECK (
    (actor_staff_id IS NULL AND actor_role IS NULL AND previous_status IS NULL)
    OR (actor_staff_id IS NOT NULL AND actor_role IS NOT NULL AND actor_role = 'WAITER'
        AND previous_status IS NOT NULL AND previous_status = 'READY')
);
