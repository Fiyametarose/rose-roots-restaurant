CREATE TABLE catalog_translation (
    target_type text NOT NULL CHECK (target_type IN ('CATEGORY','DISH')),
    target_id uuid NOT NULL,
    locale text NOT NULL CHECK (locale IN ('am','ar')),
    name varchar(120) NOT NULL,
    description varchar(1500) NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    PRIMARY KEY(target_type,target_id,locale),
    CHECK (target_type <> 'CATEGORY' OR description = '')
);

CREATE TABLE catalog_translation_audit (
    target_type text NOT NULL,
    target_id uuid NOT NULL,
    locale text NOT NULL,
    version bigint NOT NULL,
    manager_username text NOT NULL,
    name varchar(120) NOT NULL,
    description varchar(1500) NOT NULL,
    saved_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (target_type,target_id,locale,version),
    FOREIGN KEY (target_type,target_id,locale) REFERENCES catalog_translation(target_type,target_id,locale)
);
