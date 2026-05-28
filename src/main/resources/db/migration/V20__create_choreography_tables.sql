CREATE TABLE choreography (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255)  NOT NULL,
    description     TEXT,
    dance_type_id   UUID          NOT NULL REFERENCES dance_type(id) ON DELETE CASCADE,
    owner_id        UUID          NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    is_public       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE choreography_entry (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    choreography_id UUID          NOT NULL REFERENCES choreography(id) ON DELETE CASCADE,
    entry_type      VARCHAR(20)   NOT NULL, -- 'FIGURE' or 'SECTION_LABEL'
    dance_figure_id UUID                   REFERENCES dance_figure(id) ON DELETE SET NULL,
    section_label   VARCHAR(255),
    line_indicator  VARCHAR(20),            -- SHORT_WALL, LONG_WALL, DIAGONAL, CORNER
    notes           VARCHAR(500),
    sort_order      INTEGER       NOT NULL,
    CONSTRAINT unique_choreography_sort_order UNIQUE (choreography_id, sort_order)
);

CREATE INDEX idx_choreography_owner ON choreography(owner_id);
CREATE INDEX idx_choreography_dance_type ON choreography(dance_type_id);
CREATE INDEX idx_choreography_entry_choreo ON choreography_entry(choreography_id);
