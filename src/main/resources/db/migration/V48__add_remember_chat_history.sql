-- Migration V48: "Remember chat history" assistant preference (#310).
-- On by default, so nothing changes until a user opts out.

ALTER TABLE user_assistant_preferences
    ADD COLUMN remember_chat_history BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE assistant_conversation
    ADD COLUMN ephemeral BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN session_id VARCHAR(100);

CREATE INDEX idx_assistant_conversation_ephemeral ON assistant_conversation(ephemeral);
CREATE INDEX idx_assistant_conversation_session ON assistant_conversation(session_id);
