-- Migration V37: the AI assistant's saved conversations (#148).
-- Private to their owner: every query filters on owner_id. Drafts arrive with the next issue.

CREATE TABLE assistant_conversation (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    UUID         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    title       VARCHAR(80)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_assistant_conversation_owner ON assistant_conversation(owner_id, updated_at DESC);

CREATE TABLE assistant_message (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id  UUID         NOT NULL REFERENCES assistant_conversation(id) ON DELETE CASCADE,
    position         INT          NOT NULL,
    role             VARCHAR(20)  NOT NULL,
    content          TEXT         NOT NULL,
    tool_payload     JSONB,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_assistant_message_position UNIQUE (conversation_id, position)
);
