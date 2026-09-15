package ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response returned by {@code /auth/register}, {@code /auth/login}, and {@code /auth/refresh}. */
public class AuthResponse {

  @Schema(description = "Access token (JWT)", example = "eyJhbGciOiJIUzI1NiJ9...")
  private String token;

  @Schema(description = "Refresh token (JWT)", example = "eyJhbGciOiJIUzI1NiJ9...")
  private String refreshToken;

  @Schema(description = "Unique user identifier", example = "abc123")
  private String userId;

  @Schema(description = "User email", example = "user@example.com")
  private String email;

  @Schema(
      description = "Resolved role: ADMIN or USER",
      example = "USER",
      allowableValues = {"USER", "ADMIN"})
  private String role;

  @Schema(description = "Display name", example = "John Doe")
  private String name;

  public AuthResponse() {}

  public AuthResponse(
      String token, String refreshToken, String userId, String email, String role, String name) {
    this.token = token;
    this.refreshToken = refreshToken;
    this.userId = userId;
    this.email = email;
    this.role = role;
    this.name = name;
  }

  public static AuthResponseBuilder builder() {
    return new AuthResponseBuilder();
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public String getRefreshToken() {
    return refreshToken;
  }

  public void setRefreshToken(String refreshToken) {
    this.refreshToken = refreshToken;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public static class AuthResponseBuilder {
    private String token;
    private String refreshToken;
    private String userId;
    private String email;
    private String role;
    private String name;

    public AuthResponseBuilder token(String token) {
      this.token = token;
      return this;
    }

    public AuthResponseBuilder refreshToken(String refreshToken) {
      this.refreshToken = refreshToken;
      return this;
    }

    public AuthResponseBuilder userId(String userId) {
      this.userId = userId;
      return this;
    }

    public AuthResponseBuilder email(String email) {
      this.email = email;
      return this;
    }

    public AuthResponseBuilder role(String role) {
      this.role = role;
      return this;
    }

    public AuthResponseBuilder name(String name) {
      this.name = name;
      return this;
    }

    public AuthResponse build() {
      return new AuthResponse(token, refreshToken, userId, email, role, name);
    }
  }
}
