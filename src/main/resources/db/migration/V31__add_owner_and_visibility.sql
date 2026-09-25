-- 1. Create share table
CREATE TABLE share (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_type       VARCHAR(50) NOT NULL,
    item_id         UUID NOT NULL,
    grantee_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    permission      VARCHAR(50) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_share_lookup ON share(item_type, item_id, grantee_user_id);

-- 2. Add owner and visibility to material
ALTER TABLE material ADD COLUMN owner_id UUID;

-- Backfill owner_id from activity_event (earliest MATERIAL_CREATED event for that material)
UPDATE material m
SET owner_id = ae.actor_id
FROM (
    SELECT DISTINCT ON (target_id) target_id, actor_id
    FROM activity_event
    WHERE target_type = 'MATERIAL' AND event_type = 'MATERIAL_CREATED' AND target_id IS NOT NULL
    ORDER BY target_id, created_at ASC
) ae
WHERE m.id = ae.target_id;

-- Fallback to first ADMIN user for any materials without an event
UPDATE material
SET owner_id = (SELECT id FROM app_user WHERE role = 'ADMIN' ORDER BY created_at ASC LIMIT 1)
WHERE owner_id IS NULL;

-- If there are still any nulls (e.g. no admin), fallback to any user
UPDATE material
SET owner_id = (SELECT id FROM app_user ORDER BY created_at ASC LIMIT 1)
WHERE owner_id IS NULL;

ALTER TABLE material
    ALTER COLUMN owner_id SET NOT NULL,
    ADD CONSTRAINT fk_material_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE INDEX idx_material_owner ON material(owner_id);

-- Every existing note becomes PUBLIC, but default for future notes is PRIVATE
ALTER TABLE material ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE material ALTER COLUMN visibility SET DEFAULT 'PRIVATE';

-- 3. Replace is_public with visibility on custom_list
ALTER TABLE custom_list ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE';
UPDATE custom_list SET visibility = CASE WHEN is_public = TRUE THEN 'PUBLIC' ELSE 'PRIVATE' END;
ALTER TABLE custom_list DROP COLUMN is_public;

-- 4. Replace is_public with visibility on choreography
ALTER TABLE choreography ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE';
UPDATE choreography SET visibility = CASE WHEN is_public = TRUE THEN 'PUBLIC' ELSE 'PRIVATE' END;
ALTER TABLE choreography DROP COLUMN is_public;

-- 5. Add target_visibility to activity_event and backfill existing MATERIAL events as PUBLIC
ALTER TABLE activity_event ADD COLUMN target_visibility VARCHAR(20);
UPDATE activity_event SET target_visibility = 'PUBLIC' WHERE target_type = 'MATERIAL';
