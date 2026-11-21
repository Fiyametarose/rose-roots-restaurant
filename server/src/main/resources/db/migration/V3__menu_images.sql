CREATE TABLE catalog_menu_image (
    id uuid PRIMARY KEY,
    storage_reference varchar(40) NOT NULL UNIQUE,
    content_type varchar(32) NOT NULL CHECK (content_type = 'image/jpeg'),
    width integer NOT NULL CHECK (width BETWEEN 1 AND 1600),
    height integer NOT NULL CHECK (height BETWEEN 1 AND 1600),
    file_size bigint NOT NULL CHECK (file_size > 0),
    created_at timestamptz NOT NULL,
    created_by varchar(255) NOT NULL
);
