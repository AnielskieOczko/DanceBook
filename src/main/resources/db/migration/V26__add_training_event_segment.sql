-- A training session can split across styles (1h Standard then 2h Latin), which a single
-- dance_category_id could not express. Segments carry the style and how long was spent on
-- it, so per-style time is a sum rather than a guess.
CREATE TABLE training_event_segment (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    training_event_id  UUID    NOT NULL REFERENCES training_event(id) ON DELETE CASCADE,
    dance_category_id  UUID    NOT NULL REFERENCES dance_category(id) ON DELETE CASCADE,
    duration_minutes   INTEGER NOT NULL,
    sort_order         INTEGER NOT NULL,
    CONSTRAINT unique_training_event_segment_sort_order UNIQUE (training_event_id, sort_order)
);

CREATE INDEX idx_training_event_segment_event ON training_event_segment(training_event_id);
CREATE INDEX idx_training_event_segment_category ON training_event_segment(dance_category_id);

-- Backfill: every event that already named a style becomes a single segment covering its
-- whole wall-clock duration. Events with no style stay style-less, i.e. no segments.
INSERT INTO training_event_segment (id, training_event_id, dance_category_id, duration_minutes, sort_order)
SELECT gen_random_uuid(),
       e.id,
       e.dance_category_id,
       GREATEST(CAST(EXTRACT(EPOCH FROM (e.end_time - e.start_time)) / 60 AS INTEGER), 0),
       0
FROM training_event e
WHERE e.dance_category_id IS NOT NULL;

-- The old single-style column is now redundant
DROP INDEX IF EXISTS idx_training_event_dance_category;
ALTER TABLE training_event DROP COLUMN dance_category_id;
