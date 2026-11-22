ALTER TABLE kitchen_prep_profile ADD COLUMN upper_duration_seconds integer
    CHECK (upper_duration_seconds BETWEEN duration_seconds AND 86400);

CREATE TABLE kitchen_option_workload (
    menu_item_id uuid NOT NULL REFERENCES kitchen_prep_profile(menu_item_id),
    group_id uuid NOT NULL,
    option_id uuid NOT NULL,
    lower_seconds integer NOT NULL CHECK (lower_seconds BETWEEN 0 AND 86400),
    upper_seconds integer NOT NULL CHECK (upper_seconds BETWEEN lower_seconds AND 86400),
    PRIMARY KEY (menu_item_id, group_id, option_id)
);

ALTER TABLE accepted_order_item ADD COLUMN estimated_lower_seconds bigint;
ALTER TABLE accepted_order_item ADD COLUMN estimated_upper_seconds bigint;
ALTER TABLE accepted_order_item ADD CHECK (
    (estimated_lower_seconds IS NULL AND estimated_upper_seconds IS NULL) OR
    (station_id IS NOT NULL AND estimated_lower_seconds IS NOT NULL AND estimated_upper_seconds IS NOT NULL
        AND estimated_lower_seconds > 0 AND estimated_upper_seconds >= estimated_lower_seconds)
);
