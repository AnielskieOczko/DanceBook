-- Figures are shared and edited by the community (#152).

-- 1. Who created each figure. Syllabus imports stay NULL; deleting a user keeps their figures.
ALTER TABLE dance_figure ADD COLUMN created_by_id UUID REFERENCES app_user(id) ON DELETE SET NULL;

-- Backfill from the earliest DANCE_FIGURE_CREATED event for each user-created figure
UPDATE dance_figure df
SET created_by_id = ae.actor_id
FROM (
    SELECT DISTINCT ON (target_id) target_id, actor_id
    FROM activity_event
    WHERE target_type = 'DANCE_FIGURE' AND event_type = 'DANCE_FIGURE_CREATED' AND target_id IS NOT NULL
    ORDER BY target_id, created_at ASC
) ae
WHERE df.id = ae.target_id AND df.predefined = FALSE;

CREATE INDEX idx_dance_figure_created_by ON dance_figure(created_by_id);

-- 2. Optimistic locking, so two people saving the same figure get a conflict instead of an overwrite
ALTER TABLE dance_figure ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
