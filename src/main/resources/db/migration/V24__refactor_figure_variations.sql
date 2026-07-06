-- Create dance_figure_variation table
CREATE TABLE dance_figure_variation (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dance_figure_id        UUID NOT NULL REFERENCES dance_figure(id) ON DELETE CASCADE,
    name                   VARCHAR(255) NOT NULL,
    timing                 VARCHAR(255) NOT NULL,
    is_default             BOOLEAN NOT NULL DEFAULT false,
    starting_foot_leader   VARCHAR(50),
    ending_foot_leader     VARCHAR(50),
    starting_foot_follower VARCHAR(50),
    ending_foot_follower   VARCHAR(50),
    starting_position      VARCHAR(255),
    ending_position        VARCHAR(255),
    CONSTRAINT unique_dance_figure_variation_name UNIQUE (dance_figure_id, name)
);

-- Add variation foreign key column to dance_figure_step
ALTER TABLE dance_figure_step ADD COLUMN dance_figure_variation_id UUID;

-- Indexes for performance and integrity
CREATE INDEX idx_dance_figure_variation ON dance_figure_variation(dance_figure_id);
CREATE UNIQUE INDEX idx_default_variation ON dance_figure_variation(dance_figure_id) WHERE is_default = true;
CREATE INDEX idx_dance_figure_step_variation ON dance_figure_step(dance_figure_variation_id);

-- Step 1: Migrate existing figures to their default 'Standard' variation
INSERT INTO dance_figure_variation (
    id, dance_figure_id, name, timing, is_default,
    starting_foot_leader, ending_foot_leader, starting_foot_follower, ending_foot_follower,
    starting_position, ending_position
)
SELECT 
    gen_random_uuid(),
    df.id,
    'Standard',
    COALESCE(
        (
            SELECT string_agg(timing, '') 
            FROM (
                SELECT timing 
                FROM dance_figure_step 
                WHERE dance_figure_id = df.id AND role = 'LEADER' 
                ORDER BY step_number
            ) s
        ),
        'Standard'
    ),
    true,
    df.starting_foot_leader,
    df.ending_foot_leader,
    df.starting_foot_follower,
    df.ending_foot_follower,
    df.starting_position,
    df.ending_position
FROM dance_figure df;

-- Step 2: Map existing steps to the new 'Standard' variation
UPDATE dance_figure_step dfs
SET dance_figure_variation_id = dfv.id
FROM dance_figure_variation dfv
WHERE dfs.dance_figure_id = dfv.dance_figure_id AND dfv.name = 'Standard';

-- Step 3: Parse alternative_timing strings and create new variations
INSERT INTO dance_figure_variation (
    id, dance_figure_id, name, timing, is_default
)
SELECT 
    gen_random_uuid(),
    df.id,
    'Alternative (' || trim(t.timing) || ')',
    trim(t.timing),
    false
FROM dance_figure df,
LATERAL regexp_split_to_table(df.alternative_timing, ',\s*') AS t(timing)
WHERE df.alternative_timing IS NOT NULL AND trim(t.timing) != '';

-- Step 4: Clean up old schema
ALTER TABLE dance_figure_step ALTER COLUMN dance_figure_variation_id SET NOT NULL;
ALTER TABLE dance_figure_step DROP COLUMN dance_figure_id;
ALTER TABLE dance_figure_step ADD CONSTRAINT fk_dance_figure_step_variation FOREIGN KEY (dance_figure_variation_id) REFERENCES dance_figure_variation(id) ON DELETE CASCADE;

ALTER TABLE dance_figure DROP COLUMN alternative_timing;
ALTER TABLE dance_figure DROP COLUMN starting_foot_leader;
ALTER TABLE dance_figure DROP COLUMN ending_foot_leader;
ALTER TABLE dance_figure DROP COLUMN starting_foot_follower;
ALTER TABLE dance_figure DROP COLUMN ending_foot_follower;
ALTER TABLE dance_figure DROP COLUMN starting_position;
ALTER TABLE dance_figure DROP COLUMN ending_position;
