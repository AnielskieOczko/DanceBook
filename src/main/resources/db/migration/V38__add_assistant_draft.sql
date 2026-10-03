-- Migration V38: what the assistant drafted for the user to confirm (#149).
-- A draft belongs to a conversation, so it is private to that conversation's owner and goes
-- when the conversation does. message_id is the tool message that produced it; it is set just
-- after that message is stored, so it starts null. notice is the error of a failed Save while
-- the draft is PENDING, or the warning of a Save that succeeded with a caveat.

CREATE TABLE assistant_draft (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id  UUID         NOT NULL REFERENCES assistant_conversation(id) ON DELETE CASCADE,
    message_id       UUID         REFERENCES assistant_message(id) ON DELETE CASCADE,
    kind             VARCHAR(20)  NOT NULL,
    payload          JSONB        NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    saved_entity_id  UUID,
    notice           TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_assistant_draft_conversation ON assistant_draft(conversation_id);
