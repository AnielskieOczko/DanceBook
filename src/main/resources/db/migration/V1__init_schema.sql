
CREATE TABLE dance_type (
                            id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            name        VARCHAR(100) NOT NULL UNIQUE,
                            predefined  BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE dance_category (
                                id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                name        VARCHAR(100) NOT NULL UNIQUE,
                                predefined  BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE material (
                          id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          name            VARCHAR(255) NOT NULL,
                          description     TEXT,
                          dance_type_id   UUID REFERENCES dance_type(id),
                          dance_category_id UUID REFERENCES dance_category(id),
                          rating          SMALLINT CHECK (rating BETWEEN 1 AND 5),
                          video_link      VARCHAR(500),
                          source_link     VARCHAR(500),
                          drive_file_id   VARCHAR(255),
                          created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE figure (
                        id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        material_id UUID NOT NULL REFERENCES material(id) ON DELETE CASCADE,
                        name        VARCHAR(255) NOT NULL,
                        start_time  INTEGER NOT NULL,
                        end_time    INTEGER NOT NULL
);