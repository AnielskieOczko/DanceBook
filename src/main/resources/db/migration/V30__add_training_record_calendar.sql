-- Statistics and history scope to the active calendar, but training_record deliberately has
-- no foreign key to training_event (the record outlives the session), so it cannot reach a
-- calendar by joining. It keeps its own copy, exactly as it already copies title, event_type
-- and category_name.
ALTER TABLE training_record ADD COLUMN calendar_id   UUID;
ALTER TABLE training_record ADD COLUMN calendar_name VARCHAR(255);

-- Composite, matching the query shape the scoped statistics and history reads use:
-- filter by owner and calendar, order by when it happened. Mirrors
-- idx_training_record_created_by_occurred, which does the same for the unscoped reads.
CREATE INDEX idx_training_record_created_by_calendar_occurred
    ON training_record(created_by_id, calendar_id, occurred_at DESC);

-- Backfill from each record's session, where that session still exists. Unlike V29's backfill
-- this needs no environment variable, so it belongs in the migration rather than a startup
-- component. Records already orphaned keep a null calendar: the calendar they came from is
-- genuinely unrecoverable, and they show under "All calendars" only.
UPDATE training_record r
SET calendar_id   = e.calendar_id,
    calendar_name = c.display_name
FROM training_event e
JOIN training_calendar c ON c.id = e.calendar_id
WHERE r.training_event_id = e.id;
