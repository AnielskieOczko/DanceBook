-- Migration V46: "Show streaks and badges" preference (#317).
-- On by default, so nothing changes until a user opts out.

ALTER TABLE user_assistant_preferences
    ADD COLUMN show_streaks_and_badges BOOLEAN NOT NULL DEFAULT TRUE;
