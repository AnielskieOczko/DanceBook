-- The BCrypt hash below represents 'password123'
UPDATE app_user
SET password = '$2a$10$KuStV0SkNUExMtl9ZscTtOQPlP2UYE7hsGOax7isN55kQ8Coz8DW.',
    email = 'rafaljankowski7@gmail.com'
WHERE username = 'rafal';

UPDATE app_user
SET password = '$2a$10$KuStV0SkNUExMtl9ZscTtOQPlP2UYE7hsGOax7isN55kQ8Coz8DW.',
    email = 'test@example.com'
WHERE username = 'test_user';
