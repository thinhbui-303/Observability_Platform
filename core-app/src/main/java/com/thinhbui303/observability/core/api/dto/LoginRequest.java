package com.thinhbui303.observability.core.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Payload for authenticating a user")
public class LoginRequest {
    @Schema(description = "Username (e.g. admin or devops)", example = "admin")
    private String username;
    @Schema(description = "Password", example = "password")
    private String password;

    public LoginRequest() {
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
