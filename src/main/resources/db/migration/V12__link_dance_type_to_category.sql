-- Link dance types to categories (parent-child relationship)

-- 1. Add category_id FK to dance_type (required)
ALTER TABLE dance_type ADD COLUMN category_id UUID REFERENCES dance_category(id);

-- 2. Seed relationships for existing predefined types
UPDATE dance_type SET category_id = (SELECT id FROM dance_category WHERE name = 'Standard')
WHERE name IN ('Waltz', 'Tango', 'Viennese Waltz', 'Foxtrot', 'Quickstep');

UPDATE dance_type SET category_id = (SELECT id FROM dance_category WHERE name = 'Latin')
WHERE name IN ('Cha Cha', 'Samba', 'Rumba', 'Paso Doble', 'Jive');

-- 3. Rename "Modern Jive" category to "Social", add "Modern Jive" as a dance type
UPDATE dance_category SET name = 'Social' WHERE name = 'Modern Jive';

INSERT INTO dance_type (name, predefined, category_id)
VALUES ('Modern Jive', true, (SELECT id FROM dance_category WHERE name = 'Social'));

-- 4. Make category_id NOT NULL now that all types have a category
ALTER TABLE dance_type ALTER COLUMN category_id SET NOT NULL;

-- 5. Drop redundant dance_category_id from material (category is derived from type)
ALTER TABLE material DROP COLUMN dance_category_id;
