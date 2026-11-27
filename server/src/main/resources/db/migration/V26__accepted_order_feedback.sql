ALTER TABLE accepted_order_item ADD UNIQUE (order_id, id);

CREATE TABLE accepted_order_feedback (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES accepted_order(id),
    item_id uuid,
    request_key uuid NOT NULL,
    rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    topics jsonb NOT NULL CHECK (jsonb_typeof(topics) = 'array'),
    comment varchar(500) NOT NULL,
    menu_item_id uuid,
    dish_name text,
    submitted_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (order_id, item_id) REFERENCES accepted_order_item(order_id, id),
    CHECK ((item_id IS NULL AND menu_item_id IS NULL AND dish_name IS NULL)
        OR (item_id IS NOT NULL AND menu_item_id IS NOT NULL AND dish_name IS NOT NULL AND topics = '[]'::jsonb))
);

CREATE UNIQUE INDEX accepted_order_feedback_order ON accepted_order_feedback(order_id) WHERE item_id IS NULL;
CREATE UNIQUE INDEX accepted_order_feedback_item ON accepted_order_feedback(item_id) WHERE item_id IS NOT NULL;
CREATE INDEX accepted_order_feedback_feed ON accepted_order_feedback(submitted_at DESC, id DESC);
