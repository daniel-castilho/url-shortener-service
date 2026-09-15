package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.outgoing.AdminEmailPort;
import ca.tyny.urlshortener.core.ports.outgoing.AuthenticationPort;
import ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort;
import ca.tyny.urlshortener.core.ports.outgoing.PasswordEncoderPort;
import ca.tyny.urlshortener.core.ports.outgoing.TokenPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;

public class UserService {

  private final UserRepositoryPort userRepository;
  private final PasswordEncoderPort passwordEncoder;
  private final TokenPort tokenPort;
  private final AuthenticationPort authenticationPort;
  private final IdGeneratorPort idGeneratorPort;
  private final AdminEmailPort adminEmailPort;

  public UserService(
      UserRepositoryPort userRepository,
      PasswordEncoderPort passwordEncoder,
      TokenPort tokenPort,
      AuthenticationPort authenticationPort,
      IdGeneratorPort idGeneratorPort,
      AdminEmailPort adminEmailPort) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.tokenPort = tokenPort;
    this.authenticationPort = authenticationPort;
    this.idGeneratorPort = idGeneratorPort;
    this.adminEmailPort = adminEmailPort;
  }

  /** Resolves the role from the configured admin email list (ADR 0011 D1). */
  private String role(String email) {
    return adminEmailPort.isAdminEmail(email) ? "ADMIN" : "USER";
  }

  public AuthResult register(String email, String name, String password) {
    if (userRepository.findByEmail(email).isPresent()) {
      throw new IllegalArgumentException("Email already in use");
    }

    String userId = idGeneratorPort.generateId();

    User user = User.createFreeUser(userId, email, name, passwordEncoder.encode(password));

    userRepository.save(user);

    String token = tokenPort.generateToken(user.email(), role(user.email()));
    String refreshToken = tokenPort.generateRefreshToken(user.email());

    return new AuthResult(
        token, refreshToken, user.id(), user.email(), role(user.email()), user.name());
  }

  public AuthResult login(String email, String password) {
    authenticationPort.authenticate(email, password);

    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    enforceNotBlocked(user);

    String token = tokenPort.generateToken(user.email(), role(user.email()));
    String refreshToken = tokenPort.generateRefreshToken(user.email());

    return new AuthResult(
        token, refreshToken, user.id(), user.email(), role(user.email()), user.name());
  }

  public AuthResult refreshToken(String refreshToken) {
    if (!tokenPort.validateToken(refreshToken)) {
      throw new IllegalArgumentException("Invalid refresh token");
    }

    String email = tokenPort.getUsernameFromToken(refreshToken);
    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    enforceNotBlocked(user);

    String newToken = tokenPort.generateToken(email, role(email));

    return new AuthResult(
        newToken, refreshToken, user.id(), user.email(), role(user.email()), user.name());
  }

  /**
   * Guards the authentication paths {login, refresh} against blocked accounts (ADR 0011 D3).
   *
   * <p>Credential validation runs first (401 for wrong credentials); only after a valid credential
   * does a blocked account answer 403 "Account blocked." — the 403 leaks no information about
   * credentials, the 401 leaks no information about the block.
   */
  private void enforceNotBlocked(User user) {
    if (user.blocked()) {
      throw new ForbiddenException("Account blocked.");
    }
  }

  /**
   * Returns the identity of the user authenticated via the validated JWT principal (email).
   *
   * <p>Reuses the {@code findByEmail} lookup already used by {@link #refreshToken(String)}. The
   * tokens in the result are {@code null} — the caller (REST adapter) exposes only the identity and
   * the resolved role.
   */
  public AuthResult me(String email) {
    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
    return new AuthResult(null, null, user.id(), user.email(), role(user.email()), user.name());
  }

  /** Domain result object for authentication operations. */
  public record AuthResult(
      String token, String refreshToken, String userId, String email, String role, String name) {}
}
