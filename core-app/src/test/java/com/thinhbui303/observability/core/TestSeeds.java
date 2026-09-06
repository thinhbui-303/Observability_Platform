package com.thinhbui303.observability.core;

import com.thinhbui303.observability.core.domain.UserEntity;
import com.thinhbui303.observability.core.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class TestSeeds {

    private TestSeeds() {
    }

    public static void seedUserWithRole(JdbcTemplate jdbcTemplate, UserRepository userRepository,
                                        PasswordEncoder encoder, String username, String role) {
        jdbcTemplate.execute("INSERT INTO roles (name) VALUES ('" + role + "') ON CONFLICT (name) DO NOTHING");
        if (userRepository.findByUsername(username).isEmpty()) {
            UserEntity u = new UserEntity();
            u.setUsername(username);
            u.setPasswordHash(encoder.encode("password123"));
            UserEntity saved = userRepository.save(u);
            jdbcTemplate.execute("INSERT INTO user_roles (user_id, role_id) VALUES (" + saved.getId() +
                    ", (SELECT id FROM roles WHERE name = '" + role + "'))");
        }
    }

    public static void deleteTestUsers(JdbcTemplate jdbcTemplate, String... usernames) {
        for (String u : usernames) {
            jdbcTemplate.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username = ?)", u);
            jdbcTemplate.update("DELETE FROM users WHERE username = ?", u);
        }
    }
}
