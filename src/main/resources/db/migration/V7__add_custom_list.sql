CREATE TABLE custom_list (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100)  NOT NULL,
    owner_id        UUID          NOT NULL REFERENCES app_user(id),
    name_filter     VARCHAR(255),
    min_rating      SMALLINT,
    is_public       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE custom_list_dance_type (
    list_id       UUID NOT NULL REFERENCES custom_list(id) ON DELETE CASCADE,
    dance_type_id UUID NOT NULL REFERENCES dance_type(id) ON DELETE CASCADE,
    PRIMARY KEY (list_id, dance_type_id)
);

CREATE TABLE custom_list_dance_category (
    list_id           UUID NOT NULL REFERENCES custom_list(id) ON DELETE CASCADE,
    dance_category_id UUID NOT NULL REFERENCES dance_category(id) ON DELETE CASCADE,
    PRIMARY KEY (list_id, dance_category_id)
);