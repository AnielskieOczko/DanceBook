CREATE TABLE uploaded_file (
    drive_file_id VARCHAR(255) PRIMARY KEY,
    uploader_id   UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_uploaded_file_uploader ON uploaded_file(uploader_id);

-- Backfill from existing materials to preserve their uploaded video links
INSERT INTO uploaded_file (drive_file_id, uploader_id, created_at)
SELECT drive_file_id, owner_id, NOW()
FROM material
WHERE drive_file_id IS NOT NULL
ON CONFLICT (drive_file_id) DO NOTHING;
