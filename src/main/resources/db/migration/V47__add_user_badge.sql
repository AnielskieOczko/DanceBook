-- Migration V47: Earned training badges per user (#39).
--
-- Badges are evaluated from training_record and stored permanently once earned; they are
-- never revoked if a later session is skipped or edited.
--
-- month_key is '' for badges earned once ever and 'YYYY-MM' for PERFECT_MONTH, which can be
-- earned once per finished calendar month. training_event_id is held without a foreign key so
-- deleting a session never deletes or revokes an earned badge.

CREATE TABLE user_badge (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    badge_type        VARCHAR(40) NOT NULL,
    month_key         VARCHAR(7)  NOT NULL DEFAULT '',
    training_event_id UUID,
    earned_at         TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_user_badge_type_month UNIQUE (user_id, badge_type, month_key)
);

CREATE INDEX idx_user_badge_user ON user_badge(user_id, earned_at DESC);
CREATE INDEX idx_user_badge_event ON user_badge(user_id, training_event_id);
