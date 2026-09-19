INSERT INTO roles (name, description) VALUES ('ADMIN', 'System Administrator') ON CONFLICT (name) DO NOTHING;
INSERT INTO roles (name, description) VALUES ('DEVOPS', 'DevOps Engineer') ON CONFLICT (name) DO NOTHING;
INSERT INTO roles (name, description) VALUES ('VIEWER', 'Read-Only Viewer') ON CONFLICT (name) DO NOTHING;

INSERT INTO users (username, password_hash) VALUES ('admin_user', '$2a$10$GyneCEepqv2yMchMzbbyR.CYb./D1id6NtwIxm4FsU15bvfgmBEGC') ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash;
INSERT INTO users (username, password_hash) VALUES ('devops_user', '$2a$10$GyneCEepqv2yMchMzbbyR.CYb./D1id6NtwIxm4FsU15bvfgmBEGC') ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash;
INSERT INTO users (username, password_hash) VALUES ('viewer_user', '$2a$10$GyneCEepqv2yMchMzbbyR.CYb./D1id6NtwIxm4FsU15bvfgmBEGC') ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash;

DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username IN ('admin_user', 'devops_user', 'viewer_user'));

INSERT INTO user_roles (user_id, role_id) VALUES ((SELECT id FROM users WHERE username = 'admin_user'), (SELECT id FROM roles WHERE name = 'ADMIN'));
INSERT INTO user_roles (user_id, role_id) VALUES ((SELECT id FROM users WHERE username = 'devops_user'), (SELECT id FROM roles WHERE name = 'DEVOPS'));
INSERT INTO user_roles (user_id, role_id) VALUES ((SELECT id FROM users WHERE username = 'viewer_user'), (SELECT id FROM roles WHERE name = 'VIEWER'));
