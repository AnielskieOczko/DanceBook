CREATE TABLE storage_cleanup_log (
                                     id UUID PRIMARY KEY,
                                     executed_at TIMESTAMP NOT NULL,
                                     files_deleted_count INT NOT NULL,
                                     is_dry_run BOOLEAN NOT NULL,
                                     details TEXT
);
