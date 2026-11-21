ALTER TABLE ordering_credential ADD COLUMN allergy_reviewer boolean NOT NULL DEFAULT false;
ALTER TABLE ordering_credential ADD CONSTRAINT ordering_credential_reviewer_scope
    CHECK (NOT allergy_reviewer OR scope = 'KITCHEN');

ALTER TABLE accepted_order DROP CONSTRAINT accepted_order_status_check;
ALTER TABLE accepted_order ADD CONSTRAINT accepted_order_status_check
    CHECK (status IN ('RECEIVED', 'PREPARING', 'READY', 'CANCELLED'));
ALTER TABLE accepted_order ADD COLUMN payable_minor bigint;
UPDATE accepted_order SET payable_minor = subtotal_minor;
ALTER TABLE accepted_order ALTER COLUMN payable_minor SET NOT NULL;
ALTER TABLE accepted_order ADD CHECK (payable_minor BETWEEN 0 AND subtotal_minor);

ALTER TABLE accepted_order_item DROP CONSTRAINT accepted_order_item_allergy_review_status_check;
ALTER TABLE accepted_order_item ADD CONSTRAINT accepted_order_item_allergy_review_status_check
    CHECK (allergy_review_status IN ('NOT_REQUIRED', 'PENDING_ALLERGY_REVIEW', 'APPROVED_FOR_PREPARATION', 'DECLINED'));
ALTER TABLE accepted_order_item DROP CONSTRAINT accepted_order_item_preparation_status_check;
ALTER TABLE accepted_order_item ADD CONSTRAINT accepted_order_item_preparation_status_check
    CHECK (preparation_status IN ('RECEIVED', 'PREPARING', 'READY', 'CANCELLED'));
ALTER TABLE accepted_order_item ADD CHECK ((preparation_status = 'CANCELLED') = (allergy_review_status = 'DECLINED'));

CREATE TABLE order_item_allergy_review (
    item_id uuid NOT NULL REFERENCES accepted_order_item(id),
    previous_version bigint NOT NULL CHECK (previous_version > 0),
    resulting_version bigint NOT NULL CHECK (resulting_version = previous_version + 1),
    order_version bigint NOT NULL CHECK (order_version > 1),
    reviewer_id uuid NOT NULL REFERENCES ordering_credential(id),
    decision text NOT NULL CHECK (decision IN ('APPROVE', 'DECLINE')),
    reason varchar(240) NOT NULL CHECK (btrim(reason) <> ''),
    reviewed_at timestamptz NOT NULL,
    PRIMARY KEY (item_id, previous_version)
);
