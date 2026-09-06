-- Most training happens on a fixed weekday and hour. A series is the definition of that
-- pattern; it generates one independent training_event per occurrence, each with its own
-- Google event, so attendance stays per-occurrence.
CREATE TABLE training_series (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title          VARCHAR(255) NOT NULL,
    day_of_week    VARCHAR(10)  NOT NULL,                    -- MONDAY .. SUNDAY
    start_time     TIME         NOT NULL,
    end_time       TIME         NOT NULL,
    starts_on      DATE         NOT NULL,
    ends_on        DATE         NOT NULL,
    event_type     VARCHAR(20)  NOT NULL,                    -- TRAINING, CAMP, COMPETITION, WORKSHOP, OTHER
    description    TEXT,
    material_id    UUID                  REFERENCES material(id) ON DELETE SET NULL,
    materials_url  VARCHAR(1000),
    created_by_id  UUID         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_training_series_created_by ON training_series(created_by_id);

-- The style mix each generated occurrence starts with
CREATE TABLE training_series_segment (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    training_series_id  UUID    NOT NULL REFERENCES training_series(id) ON DELETE CASCADE,
    dance_category_id   UUID    NOT NULL REFERENCES dance_category(id) ON DELETE CASCADE,
    duration_minutes    INTEGER NOT NULL,
    sort_order          INTEGER NOT NULL,
    CONSTRAINT unique_training_series_segment_sort_order UNIQUE (training_series_id, sort_order)
);

CREATE INDEX idx_training_series_segment_series ON training_series_segment(training_series_id);

-- ON DELETE SET NULL is load-bearing: deleting a series must leave already-completed
-- occurrences standing as ordinary events, with their recorded attendance intact.
ALTER TABLE training_event ADD COLUMN series_id UUID REFERENCES training_series(id) ON DELETE SET NULL;

CREATE INDEX idx_training_event_series ON training_event(series_id);
