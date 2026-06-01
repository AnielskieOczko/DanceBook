-- Add syllabus execution metadata columns to dance_figure
ALTER TABLE dance_figure ADD COLUMN starting_foot_leader VARCHAR(50);
ALTER TABLE dance_figure ADD COLUMN ending_foot_leader VARCHAR(50);
ALTER TABLE dance_figure ADD COLUMN starting_foot_follower VARCHAR(50);
ALTER TABLE dance_figure ADD COLUMN ending_foot_follower VARCHAR(50);
ALTER TABLE dance_figure ADD COLUMN starting_position VARCHAR(255);
ALTER TABLE dance_figure ADD COLUMN ending_position VARCHAR(255);
ALTER TABLE dance_figure ADD COLUMN preceding_figure_names TEXT;
ALTER TABLE dance_figure ADD COLUMN following_figure_names TEXT;

-- Create dance_figure_step table for step-by-step breakdown
CREATE TABLE dance_figure_step (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dance_figure_id  UUID NOT NULL REFERENCES dance_figure(id) ON DELETE CASCADE,
    step_number      INT NOT NULL,
    timing           VARCHAR(50) NOT NULL,
    role             VARCHAR(50) NOT NULL,
    foot             VARCHAR(50) NOT NULL,
    action           TEXT NOT NULL,
    footwork         VARCHAR(255),
    alignment        TEXT,
    amount_of_turn   TEXT
);

-- Index for fast step lookups per figure
CREATE INDEX idx_dance_figure_step_figure ON dance_figure_step(dance_figure_id);
