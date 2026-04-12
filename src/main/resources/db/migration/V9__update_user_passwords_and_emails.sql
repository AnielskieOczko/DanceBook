-- The BCrypt hash below represents 'password123'
UPDATE app_user
SET password = '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HCGF4rI9zYnC0A4xXbX7O',
    email = 'rafaljankowski7@gmail.com'
WHERE username = 'rafal';

UPDATE app_user
SET password = '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HCGF4rI9zYnC0A4xXbX7O',
    email = 'test@example.com'
WHERE username = 'test_user';
