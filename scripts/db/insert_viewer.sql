
INSERT INTO users (username, password_hash) VALUES ('viewer_user', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIvi');
INSERT INTO user_roles (user_id, role_id) VALUES ((SELECT id FROM users WHERE username = 'viewer_user'), 2);

