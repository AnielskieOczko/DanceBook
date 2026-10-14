-- Migration V45: "Include shared items" assistant preference (#311).
-- On by default, so nothing changes until a user opts out.

ALTER TABLE user_assistant_preferences
    ADD COLUMN include_shared_items BOOLEAN NOT NULL DEFAULT TRUE;
