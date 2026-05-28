-- Add notes column to dance_figure to hold general figure instructions and alternative finishing options
ALTER TABLE dance_figure ADD COLUMN notes TEXT;

-- Create table for storing nested comments and styling instructions under each figure step
CREATE TABLE dance_figure_step_comment (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dance_figure_step_id  UUID NOT NULL REFERENCES dance_figure_step(id) ON DELETE CASCADE,
    comment_text          TEXT NOT NULL,
    display_order         INT NOT NULL
);

-- Index for optimizing comment lookups per step
CREATE INDEX idx_dance_figure_step_comment_step ON dance_figure_step_comment(dance_figure_step_id);
