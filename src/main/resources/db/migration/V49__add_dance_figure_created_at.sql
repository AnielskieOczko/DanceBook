-- Migration V49: creation date on figures, shown in the mobile Figures list.
-- Backfilled from the earliest DANCE_FIGURE_CREATED activity event. Figures with no such event
-- (syllabus imports and older custom figures) stay NULL and show no date.

ALTER TABLE dance_figure
    ADD COLUMN created_at TIMESTAMP;

UPDATE dance_figure f
SET created_at = e.first_created
FROM (
    SELECT target_id, MIN(created_at) AS first_created
    FROM activity_event
    WHERE event_type = 'DANCE_FIGURE_CREATED' AND target_id IS NOT NULL
    GROUP BY target_id
) e
WHERE e.target_id = f.id;
