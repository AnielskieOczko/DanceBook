-- Create dance_figure_step_set table
CREATE TABLE dance_figure_step_set (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dance_figure_id  UUID NOT NULL REFERENCES dance_figure(id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    is_default       BOOLEAN NOT NULL DEFAULT FALSE
);

-- Index for fast step set lookups per figure
CREATE INDEX idx_dance_figure_step_set_figure ON dance_figure_step_set(dance_figure_id);

-- Add step_set_id to steps
ALTER TABLE dance_figure_step ADD COLUMN dance_figure_step_set_id UUID REFERENCES dance_figure_step_set(id) ON DELETE CASCADE;

-- Insert a default step set for every dance_figure that currently has steps
INSERT INTO dance_figure_step_set (id, dance_figure_id, name, is_default)
SELECT DISTINCT gen_random_uuid(), dance_figure_id, 'Default', TRUE
FROM dance_figure_step;

-- Update the existing steps to reference their new step sets
UPDATE dance_figure_step s
SET dance_figure_step_set_id = ss.id
FROM dance_figure_step_set ss
WHERE s.dance_figure_id = ss.dance_figure_id;

-- Make the foreign key NOT NULL now that existing data is mapped
ALTER TABLE dance_figure_step ALTER COLUMN dance_figure_step_set_id SET NOT NULL;

-- Remove old index and column
DROP INDEX IF EXISTS idx_dance_figure_step_figure;
ALTER TABLE dance_figure_step DROP COLUMN dance_figure_id;

-- Index for fast step lookups per step set
CREATE INDEX idx_dance_figure_step_set ON dance_figure_step(dance_figure_step_set_id);
