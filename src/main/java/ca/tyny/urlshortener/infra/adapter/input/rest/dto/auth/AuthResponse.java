package ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth;

public class AuthResponse {

  private String token;
  private String refreshToken;
  private String userId;
  private String email;
  private String name;

  public AuthResponse() {}

  public AuthResponse(String token, String refreshToken, String userId, String email, String name) {
    this.token = token;
    this.refreshToken = refreshToken;
    this.userId = userId;
    this.email = email;
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

    public AuthResponseBuilder name(String name) {
      this.name = name;
      return this;
    }

    public AuthResponse build() {
      return new AuthResponse(token, refreshToken, userId, email, name);
    }
  }
}
