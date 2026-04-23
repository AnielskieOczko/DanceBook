CREATE TABLE comment (
    id UUID PRIMARY KEY,
    material_id UUID NOT NULL REFERENCES material(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_comment_material_id ON comment(material_id);
