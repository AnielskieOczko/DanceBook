CREATE TABLE system_setting (
    id UUID PRIMARY KEY,
    setting_key VARCHAR(255) NOT NULL UNIQUE,
    setting_value VARCHAR(1024) NOT NULL
);

-- Insert default configurations
INSERT INTO system_setting (id, setting_key, setting_value) VALUES (gen_random_uuid(), 'polling_interval_minutes', '5');
INSERT INTO system_setting (id, setting_key, setting_value) VALUES (gen_random_uuid(), 'auto_logout_minutes', '10');
