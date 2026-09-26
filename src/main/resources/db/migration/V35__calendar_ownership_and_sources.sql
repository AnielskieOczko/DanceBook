-- Migration V35: Calendar ownership, per-user default, and multi-source calendar support

-- 1. Add owner, visibility, and color to training_calendar
ALTER TABLE training_calendar ADD COLUMN owner_id UUID;
ALTER TABLE training_calendar ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE';
ALTER TABLE training_calendar ADD COLUMN color VARCHAR(50);

-- Backfill owner_id from earliest ADMIN user, or first user
UPDATE training_calendar
SET owner_id = (SELECT id FROM app_user WHERE role = 'ADMIN' ORDER BY created_at ASC LIMIT 1)
WHERE owner_id IS NULL;

UPDATE training_calendar
SET owner_id = (SELECT id FROM app_user ORDER BY created_at ASC LIMIT 1)
WHERE owner_id IS NULL;

ALTER TABLE training_calendar
    ALTER COLUMN owner_id SET NOT NULL,
    ADD CONSTRAINT fk_training_calendar_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE;

CREATE INDEX idx_training_calendar_owner ON training_calendar(owner_id);

-- Decide visibility of existing calendars:
-- If another user has sessions or attendance on it -> PUBLIC, otherwise PRIVATE
UPDATE training_calendar tc
SET visibility = CASE
    WHEN EXISTS (
        SELECT 1 FROM training_event te
        WHERE te.calendar_id = tc.id AND te.created_by_id <> tc.owner_id
    ) OR EXISTS (
        SELECT 1 FROM training_event te
        JOIN attendance a ON a.training_event_id = te.id
        WHERE te.calendar_id = tc.id AND a.user_id <> tc.owner_id
    ) THEN 'PUBLIC'
    ELSE 'PRIVATE'
END;

-- 2. Create calendar_source table for multi-source Google Calendar links
CREATE TABLE calendar_source (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    calendar_id        UUID         NOT NULL REFERENCES training_calendar(id) ON DELETE CASCADE,
    google_calendar_id VARCHAR(255) NOT NULL,
    display_name       VARCHAR(255),
    is_write_target    BOOLEAN      NOT NULL DEFAULT FALSE,
    sync_token         TEXT,
    last_synced_at     TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_calendar_source_google_per_cal UNIQUE (calendar_id, google_calendar_id)
);

CREATE UNIQUE INDEX unique_calendar_source_write_target
    ON calendar_source (calendar_id) WHERE is_write_target;

CREATE INDEX idx_calendar_source_calendar ON calendar_source(calendar_id);
CREATE INDEX idx_calendar_source_google ON calendar_source(google_calendar_id);

-- Populate calendar_source from existing training_calendar google_calendar_id
INSERT INTO calendar_source (id, calendar_id, google_calendar_id, display_name, is_write_target, sync_token, last_synced_at, created_at, updated_at)
SELECT gen_random_uuid(), id, google_calendar_id, display_name, TRUE, sync_token, last_synced_at, created_at, updated_at
FROM training_calendar;

-- Drop obsolete columns from training_calendar
DROP INDEX IF EXISTS unique_training_calendar_default;
ALTER TABLE training_calendar DROP COLUMN google_calendar_id;
ALTER TABLE training_calendar DROP COLUMN sync_token;
ALTER TABLE training_calendar DROP COLUMN is_default;

-- 3. Add default_calendar_id to app_user (replaces global is_default)
ALTER TABLE app_user ADD COLUMN default_calendar_id UUID REFERENCES training_calendar(id) ON DELETE SET NULL;
CREATE INDEX idx_app_user_default_calendar ON app_user(default_calendar_id);

-- Backfill default_calendar_id: each calendar owner gets their first calendar as default
UPDATE app_user u
SET default_calendar_id = (
    SELECT tc.id FROM training_calendar tc
    WHERE tc.owner_id = u.id
    ORDER BY tc.created_at ASC
    LIMIT 1
)
WHERE u.default_calendar_id IS NULL;

-- Backfill default_calendar_id: users with sessions or attendance on a PUBLIC calendar
UPDATE app_user u
SET default_calendar_id = (
    SELECT tc.id FROM training_calendar tc
    WHERE tc.visibility = 'PUBLIC'
      AND (
          EXISTS (
              SELECT 1 FROM training_event te
              WHERE te.calendar_id = tc.id AND te.created_by_id = u.id
          ) OR EXISTS (
              SELECT 1 FROM training_event te
              JOIN attendance a ON a.training_event_id = te.id
              WHERE te.calendar_id = tc.id AND a.user_id = u.id
          )
      )
    ORDER BY tc.created_at ASC
    LIMIT 1
)
WHERE u.default_calendar_id IS NULL;

-- 4. Add ical_uid to training_event and create training_event_source mapping table
ALTER TABLE training_event ADD COLUMN ical_uid VARCHAR(255);
CREATE INDEX idx_training_event_ical_uid ON training_event(calendar_id, ical_uid);

CREATE TABLE training_event_source (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    training_event_id  UUID         NOT NULL REFERENCES training_event(id) ON DELETE CASCADE,
    calendar_source_id UUID         NOT NULL REFERENCES calendar_source(id) ON DELETE CASCADE,
    google_event_id    VARCHAR(255) NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_event_source_mapping UNIQUE (training_event_id, calendar_source_id)
);

CREATE INDEX idx_event_source_google ON training_event_source(google_event_id);
CREATE INDEX idx_event_source_event ON training_event_source(training_event_id);

-- Backfill training_event_source from existing events
INSERT INTO training_event_source (id, training_event_id, calendar_source_id, google_event_id, created_at)
SELECT gen_random_uuid(), te.id, cs.id, te.google_event_id, te.created_at
FROM training_event te
JOIN calendar_source cs ON cs.calendar_id = te.calendar_id AND cs.is_write_target = TRUE
WHERE te.google_event_id IS NOT NULL;
