CREATE TABLE training_calendar (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    google_calendar_id VARCHAR(255) NOT NULL UNIQUE,
    display_name       VARCHAR(255) NOT NULL,
    sync_token         TEXT,                    -- unused until #53
    last_synced_at     TIMESTAMP,               -- unused until #53
    is_default         BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- At most one default. Zero is legal (no GOOGLE_CALENDAR_ID yet); "at least one" is the
-- service's rule. A unique index cannot be DEFERRABLE, so setDefault clears before it sets.
CREATE UNIQUE INDEX unique_training_calendar_default
    ON training_calendar (is_default) WHERE is_default;

-- Nullable on purpose: Flyway cannot read GOOGLE_CALENDAR_ID, so TrainingCalendarBootstrap
-- seeds the first row and backfills. ON DELETE RESTRICT: a calendar that still owns events
-- cannot be removed out from under them.
ALTER TABLE training_event
    ADD COLUMN calendar_id UUID REFERENCES training_calendar(id) ON DELETE RESTRICT;

CREATE INDEX idx_training_event_calendar ON training_event(calendar_id);
