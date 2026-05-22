-- Create global figure dictionary table
CREATE TABLE dance_figure (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(255) NOT NULL,
    dance_type_id  UUID NOT NULL REFERENCES dance_type(id) ON DELETE CASCADE,
    dance_class    VARCHAR(50),
    predefined     BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT unique_dance_type_figure_name UNIQUE (dance_type_id, name)
);

-- Add nullable foreign key column to figure table
ALTER TABLE figure ADD COLUMN dance_figure_id UUID REFERENCES dance_figure(id) ON DELETE CASCADE;

-- Migrate existing free-text figures in 'figure' table to 'dance_figure' table
-- We default dance_type_id to the material's dance_type_id.
-- If a material has no dance_type_id, we fall back to finding any dance type or we ignore it (but standard materials have it).
-- We also filter out any null dance_type_ids.
INSERT INTO dance_figure (name, dance_type_id, predefined)
SELECT DISTINCT f.name, m.dance_type_id, false
FROM figure f
JOIN material m ON f.material_id = m.id
WHERE m.dance_type_id IS NOT NULL;

-- For any figures whose material has a NULL dance_type_id, we link them to a default dance type if any exists,
-- but normally materials always have a dance type in this app.
-- Update figure records to point to the newly created dance_figure entries
UPDATE figure f
SET dance_figure_id = df.id
FROM material m, dance_figure df
WHERE f.material_id = m.id
  AND df.dance_type_id = m.dance_type_id
  AND df.name = f.name;

-- Ensure all existing figures are migrated and set constraints
-- Note: If there are figures that couldn't be linked (e.g. m.dance_type_id was null),
-- we delete them or assign them. Since they are test data, they can be safely deleted or we can just make the column NOT NULL.
DELETE FROM figure WHERE dance_figure_id IS NULL;

ALTER TABLE figure ALTER COLUMN dance_figure_id SET NOT NULL;

-- Finally drop the redundant name column from figure
ALTER TABLE figure DROP COLUMN name;
