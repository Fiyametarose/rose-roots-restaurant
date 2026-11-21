CREATE TABLE visit_review (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    session_id uuid NOT NULL UNIQUE REFERENCES dining_session(id),
    key uuid NOT NULL,
    fingerprint bytea NOT NULL CHECK (octet_length(fingerprint) = 32),
    rating integer CHECK (rating BETWEEN 1 AND 5),
    topics jsonb NOT NULL CHECK (jsonb_typeof(topics) = 'array' AND jsonb_array_length(topics) <= 8),
    comment varchar(500) NOT NULL,
    submitted_at timestamptz NOT NULL,
    follow_up text NOT NULL DEFAULT 'NEEDS_ATTENTION'
        CHECK (follow_up IN ('NEEDS_ATTENTION','FOLLOWED_UP','RESOLVED')),
    internal_response varchar(500) NOT NULL DEFAULT '',
    version bigint NOT NULL DEFAULT 1 CHECK (version BETWEEN 1 AND 9007199254740991),
    CHECK (rating IS NOT NULL OR jsonb_array_length(topics) > 0 OR btrim(comment) <> '')
);

CREATE TABLE review_follow_up_audit (
    review_id uuid NOT NULL REFERENCES visit_review(id),
    version bigint NOT NULL CHECK (version > 1),
    manager varchar(255) NOT NULL CHECK (btrim(manager) <> ''),
    from_state text NOT NULL CHECK (from_state IN ('NEEDS_ATTENTION','FOLLOWED_UP','RESOLVED')),
    to_state text NOT NULL CHECK (to_state IN ('NEEDS_ATTENTION','FOLLOWED_UP','RESOLVED')),
    internal_response varchar(500) NOT NULL,
    changed_at timestamptz NOT NULL,
    PRIMARY KEY (review_id, version)
);
