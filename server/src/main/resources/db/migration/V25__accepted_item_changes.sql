-- Preserve the original accepted subtotal while allowing reviewed edits to raise the live payable.
ALTER TABLE accepted_order DROP CONSTRAINT accepted_order_check;
ALTER TABLE accepted_order ADD CHECK (payable_minor BETWEEN 0 AND 9007199254740991);

ALTER TABLE accepted_order_item ADD COLUMN customer_cancelled boolean NOT NULL DEFAULT false;
ALTER TABLE accepted_order_item DROP CONSTRAINT accepted_order_item_check;
ALTER TABLE accepted_order_item ADD CHECK ((preparation_status = 'CANCELLED') =
    (allergy_review_status = 'DECLINED' OR customer_cancelled));
ALTER TABLE accepted_order_item ADD CHECK (NOT customer_cancelled OR allergy_review_status <> 'DECLINED');

CREATE TABLE accepted_item_change (
    session_id uuid NOT NULL REFERENCES dining_session(id),
    order_id uuid NOT NULL REFERENCES accepted_order(id),
    item_id uuid NOT NULL REFERENCES accepted_order_item(id),
    idempotency_key uuid NOT NULL,
    fingerprint bytea NOT NULL,
    terminal_credential_id uuid NOT NULL REFERENCES ordering_credential(id),
    action text NOT NULL CHECK (action IN ('EDIT','CANCEL')),
    before_snapshot jsonb NOT NULL,
    after_snapshot jsonb NOT NULL,
    result jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id,idempotency_key)
);
CREATE INDEX accepted_item_change_history ON accepted_item_change(item_id,occurred_at);
CREATE FUNCTION preserve_accepted_item_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Accepted item change history is immutable';
END;
$$;
CREATE TRIGGER accepted_item_change_immutable BEFORE UPDATE OR DELETE ON accepted_item_change
    FOR EACH ROW EXECUTE FUNCTION preserve_accepted_item_change();
