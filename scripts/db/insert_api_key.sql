
INSERT INTO services (id, name, team_owner, environment) VALUES ('test-svc', 'Test Service', 'Core Team', 'prod') ON CONFLICT DO NOTHING;
INSERT INTO service_api_keys (service_id, key_prefix, key_hash) VALUES ('test-svc', 'test-', '2688f4e126ca5efd4a60022073e6cd90017626e56c3f30b194d53e6299edfe3c');

