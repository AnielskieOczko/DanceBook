-- Per-user attendance: each attendee has their own attendance and training history.
--
-- Attendance replaces the single TrainingEvent.attendance_status column with an attendance table
-- mapping (training_event_id, user_id) -> status.
-- Training record uniqueness is updated from training_event_id to (training_event_id, created_by_id).

CREATE TABLE attendance (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    training_event_id  UUID         NOT NULL REFERENCES training_event(id) ON DELETE CASCADE,
    user_id            UUID         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    status             VARCHAR(20)  NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_attendance_event_user UNIQUE (training_event_id, user_id)
);

CREATE INDEX idx_attendance_user ON attendance(user_id);
CREATE INDEX idx_attendance_event ON attendance(training_event_id);

-- Backfill: every existing event's status belongs to its created_by user
INSERT INTO attendance (id, training_event_id, user_id, status, created_at, updated_at)
SELECT gen_random_uuid(),
       e.id,
       e.created_by_id,
       e.attendance_status,
       e.created_at,
       e.updated_at
FROM training_event e
WHERE e.attendance_status IS NOT NULL AND e.created_by_id IS NOT NULL;

-- Drop attendance_status from training_event
ALTER TABLE training_event DROP COLUMN attendance_status;

-- Training record uniqueness becomes (training_event_id, created_by_id)
ALTER TABLE training_record DROP CONSTRAINT IF EXISTS training_record_training_event_id_key;
ALTER TABLE training_record ADD CONSTRAINT unique_training_record_event_user UNIQUE (training_event_id, created_by_id);
