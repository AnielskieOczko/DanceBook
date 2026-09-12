-- Training history, kept apart from the calendar schedule.
--
-- training_event is the *schedule*: mirrored to Google Calendar, freely edited, freely
-- deleted. Statistics used to read it directly, so tidying the calendar rewrote the past --
-- delete a skipped session and your attendance rate improved. A confirmed session is now
-- also recorded here, and this table is what the statistics read.
--
-- Two conventions come straight from activity_event, the other table that outlives its
-- subject: the session is held as a plain id with no foreign key, so deleting a session
-- cannot take the record with it, and the owner reference restricts rather than cascades,
-- so deleting a user cannot silently destroy their history.
CREATE TABLE training_record (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    training_event_id UUID         NOT NULL UNIQUE,   -- no FK on purpose: the record outlives the session
    occurred_at       TIMESTAMP    NOT NULL,
    duration_minutes  INTEGER      NOT NULL,
    outcome           VARCHAR(20)  NOT NULL,          -- ATTENDED, SKIPPED
    title             VARCHAR(255) NOT NULL,
    event_type        VARCHAR(20)  NOT NULL,          -- TRAINING, CAMP, COMPETITION, WORKSHOP, OTHER
    created_by_id     UUID         NOT NULL REFERENCES app_user(id),
    orphaned_at       TIMESTAMP,                      -- stamped when the session is deleted
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Both the statistics page and the history page read one user's whole history, newest first.
CREATE INDEX idx_training_record_created_by_occurred ON training_record(created_by_id, occurred_at DESC);

-- The style breakdown, snapshotted alongside the record.
--
-- dance_category_id keeps the live link so renaming a style relabels the history that belongs
-- to it, rather than splitting one style into two. ON DELETE SET NULL means losing the
-- category costs the link and not the row; category_name is what the record then reads by.
CREATE TABLE training_record_segment (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    training_record_id UUID         NOT NULL REFERENCES training_record(id) ON DELETE CASCADE,
    dance_category_id  UUID                  REFERENCES dance_category(id) ON DELETE SET NULL,
    category_name      VARCHAR(255) NOT NULL,
    duration_minutes   INTEGER      NOT NULL,
    sort_order         INTEGER      NOT NULL,
    CONSTRAINT unique_training_record_segment_sort_order UNIQUE (training_record_id, sort_order)
);

CREATE INDEX idx_training_record_segment_record ON training_record_segment(training_record_id);

-- Backfill: every session already marked attended or skipped becomes a record, with its
-- style breakdown. Sessions deleted before this migration are gone and cannot be recovered.
INSERT INTO training_record (
    id, training_event_id, occurred_at, duration_minutes, outcome, title, event_type,
    created_by_id, orphaned_at, created_at, updated_at
)
SELECT gen_random_uuid(),
       e.id,
       e.start_time,
       GREATEST(CAST(EXTRACT(EPOCH FROM (e.end_time - e.start_time)) / 60 AS INTEGER), 0),
       e.attendance_status,
       e.title,
       e.event_type,
       e.created_by_id,
       NULL,
       NOW(),
       NOW()
FROM training_event e
WHERE e.attendance_status IN ('ATTENDED', 'SKIPPED');

INSERT INTO training_record_segment (
    id, training_record_id, dance_category_id, category_name, duration_minutes, sort_order
)
SELECT gen_random_uuid(),
       r.id,
       s.dance_category_id,
       c.name,
       s.duration_minutes,
       s.sort_order
FROM training_record r
JOIN training_event_segment s ON s.training_event_id = r.training_event_id
JOIN dance_category c ON c.id = s.dance_category_id;
