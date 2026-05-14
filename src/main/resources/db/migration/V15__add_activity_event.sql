-- Activity event log for tracking user actions (Phase 9)
CREATE TABLE activity_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(50) NOT NULL,
    actor_id        UUID NOT NULL REFERENCES app_user(id),
    target_type     VARCHAR(50) NOT NULL,
    target_id       UUID,
    target_name     VARCHAR(255),
    metadata        TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- Per-user read state for notifications
CREATE TABLE notification_read_status (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        UUID NOT NULL REFERENCES activity_event(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES app_user(id),
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    read_at         TIMESTAMP,
    UNIQUE(event_id, user_id)
);

CREATE INDEX idx_activity_event_created_at ON activity_event(created_at DESC);
CREATE INDEX idx_activity_event_actor ON activity_event(actor_id);
CREATE INDEX idx_notification_read_status_unread ON notification_read_status(user_id, is_read) WHERE is_read = FALSE;
CREATE INDEX idx_notification_read_status_event ON notification_read_status(event_id);
