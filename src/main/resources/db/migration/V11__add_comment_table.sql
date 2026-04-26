CREATE TABLE comment (
                         id UUID PRIMARY KEY,
                         content TEXT NOT NULL,
                         author_id UUID NOT NULL,
                         material_id UUID NOT NULL,
                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         updated_at TIMESTAMP,

                         CONSTRAINT fk_comment_author FOREIGN KEY (author_id) REFERENCES app_user(id) ON DELETE CASCADE,
                         CONSTRAINT fk_comment_material FOREIGN KEY (material_id) REFERENCES material(id) ON DELETE CASCADE
);
CREATE INDEX idx_comment_material_id ON comment(material_id);
CREATE INDEX idx_comment_author_id ON comment(author_id);