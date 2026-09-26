-- Migration V36: Calendar members, invites, and public subscriptions

CREATE TABLE calendar_member (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    calendar_id        UUID         NOT NULL REFERENCES training_calendar(id) ON DELETE CASCADE,
    user_id            UUID         REFERENCES app_user(id) ON DELETE CASCADE,
    invited_email      VARCHAR(255),
    role               VARCHAR(20)  NOT NULL DEFAULT 'VIEWER',
    state              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_calendar_member_calendar ON calendar_member(calendar_id);
CREATE INDEX idx_calendar_member_user ON calendar_member(user_id);
CREATE INDEX idx_calendar_member_email ON calendar_member(invited_email);

CREATE UNIQUE INDEX unique_calendar_member_user
    ON calendar_member(calendar_id, user_id) WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX unique_calendar_member_invited_email
    ON calendar_member(calendar_id, LOWER(invited_email)) WHERE user_id IS NULL;

-- Backfill active memberships:
-- Anyone other than the owner who already has sessions, attendance, records,
-- or shares on a calendar, or has it as their default calendar, becomes an ACTIVE viewer.
INSERT INTO calendar_member (id, calendar_id, user_id, role, state, created_at, updated_at)
SELECT gen_random_uuid(), tc.id, u.id, 'VIEWER', 'ACTIVE', NOW(), NOW()
FROM training_calendar tc
JOIN app_user u ON u.id <> tc.owner_id
WHERE (
    EXISTS (SELECT 1 FROM training_event te WHERE te.calendar_id = tc.id AND te.created_by_id = u.id)
    OR EXISTS (SELECT 1 FROM training_event te JOIN attendance a ON a.training_event_id = te.id WHERE te.calendar_id = tc.id AND a.user_id = u.id)
    OR EXISTS (SELECT 1 FROM training_record tr WHERE tr.calendar_id = tc.id AND tr.created_by_id = u.id)
    OR EXISTS (SELECT 1 FROM share s WHERE s.item_type = 'TRAINING_CALENDAR' AND s.item_id = tc.id AND s.grantee_user_id = u.id)
)
ON CONFLICT DO NOTHING;

-- Activity feed: filter training activity on calendar_id
ALTER TABLE activity_event ADD COLUMN calendar_id UUID;
CREATE INDEX idx_activity_event_calendar_id ON activity_event(calendar_id);
