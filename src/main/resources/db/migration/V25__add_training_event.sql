-- Training calendar: a dated training session mirrored into the dedicated Google Calendar.
-- google_event_id links the local row to its calendar counterpart; it is null only
-- transiently, between building the entity and the calendar write returning.
CREATE TABLE training_event (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    google_event_id    VARCHAR(255) UNIQUE,
    title              VARCHAR(255) NOT NULL,
    start_time         TIMESTAMP    NOT NULL,
    end_time           TIMESTAMP    NOT NULL,
    event_type         VARCHAR(20)  NOT NULL,                    -- TRAINING, CAMP, COMPETITION, WORKSHOP, OTHER
    dance_category_id  UUID                  REFERENCES dance_category(id) ON DELETE SET NULL,
    description        TEXT,
    material_id        UUID                  REFERENCES material(id) ON DELETE SET NULL,
    materials_url      VARCHAR(1000),
    attendance_status  VARCHAR(20)  NOT NULL DEFAULT 'PLANNED',  -- PLANNED, ATTENDED, SKIPPED, CANCELLED
    created_by_id      UUID         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Agenda list and every phase-2 statistic read in start_time order
CREATE INDEX idx_training_event_start_time ON training_event(start_time DESC);
CREATE INDEX idx_training_event_created_by ON training_event(created_by_id);
CREATE INDEX idx_training_event_dance_category ON training_event(dance_category_id);
