package ca.tyny.urlshortener.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.outgoing.AdminEmailPort;
import ca.tyny.urlshortener.core.ports.outgoing.AuthenticationPort;
import ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort;
import ca.tyny.urlshortener.core.ports.outgoing.PasswordEncoderPort;
import ca.tyny.urlshortener.core.ports.outgoing.TokenPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private UserRepositoryPort userRepository;
  @Mock private PasswordEncoderPort passwordEncoder;
  @Mock private TokenPort tokenPort;
  @Mock private AuthenticationPort authenticationPort;
  @Mock private IdGeneratorPort idGeneratorPort;
  @Mock private AdminEmailPort adminEmailPort;

  private UserService userService;

  @BeforeEach
  void setUp() {
    userService =
        new UserService(
            userRepository,
            passwordEncoder,
            tokenPort,
            authenticationPort,
            idGeneratorPort,
            adminEmailPort);
  }

  @Test
  @TracesRequirement("REQ-AUTH-001")
  @DisplayName("Should register new user successfully")
  void shouldRegisterUser() {
    // Given
    when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.empty());
    when(idGeneratorPort.generateId()).thenReturn("user123");
    when(passwordEncoder.encode("password123")).thenReturn("encodedPass");
    when(tokenPort.generateToken("test@example.com", "USER")).thenReturn("jwt-token");
    when(tokenPort.generateRefreshToken("test@example.com")).thenReturn("refresh-token");

    // When
    UserService.AuthResult result =
        userService.register("test@example.com", "Test User", "password123");

    // Then
    assertThat(result.token()).isEqualTo("jwt-token");
    assertThat(result.refreshToken()).isEqualTo("refresh-token");
    assertThat(result.email()).isEqualTo("test@example.com");
    assertThat(result.name()).isEqualTo("Test User");
    assertThat(result.userId()).isEqualTo("user123");
    assertThat(result.role()).isEqualTo("USER");

    verify(userRepository).save(any(User.class));
    verify(tokenPort).generateToken("test@example.com", "USER");
  }

  @Test
  @TracesRequirement("REQ-AUTH-001")
  @DisplayName("Should throw exception when registering existing email")
  void shouldThrowWhenEmailExists() {
    // Given
    when(userRepository.findByEmail("existing@example.com"))
        .thenReturn(Optional.of(mock(User.class)));

    // When/Then
    assertThatThrownBy(
            () -> userService.register("existing@example.com", "Test User", "password123"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Email already in use");

    verify(userRepository, never()).save(any());
  }

  @Test
  @TracesRequirement("REQ-AUTH-002")
  @DisplayName("Should login successfully")
  void shouldLoginUser() {
    // Given
    User user = mock(User.class);
    when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
    when(user.id()).thenReturn("user123");
    when(user.email()).thenReturn("test@example.com");
    when(user.name()).thenReturn("Test User");
    when(tokenPort.generateToken("test@example.com", "USER")).thenReturn("jwt-token");
    when(tokenPort.generateRefreshToken("test@example.com")).thenReturn("refresh-token");

    // When
    UserService.AuthResult result = userService.login("test@example.com", "password123");

    // Then
    assertThat(result.token()).isEqualTo("jwt-token");
    assertThat(result.refreshToken()).isEqualTo("refresh-token");
    assertThat(result.email()).isEqualTo("test@example.com");
    assertThat(result.role()).isEqualTo("USER");

    verify(authenticationPort).authenticate("test@example.com", "password123");
  }

  @Test
  @TracesRequirement("REQ-AUTH-003")
  @DisplayName("Should refresh token successfully")
  void shouldRefreshToken() {
    // Given
    User user = mock(User.class);
    when(tokenPort.validateToken("old-refresh-token")).thenReturn(true);
    when(tokenPort.getUsernameFromToken("old-refresh-token")).thenReturn("test@example.com");
    when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
    when(user.id()).thenReturn("user123");
    when(user.email()).thenReturn("test@example.com");
    when(user.name()).thenReturn("Test User");
    when(tokenPort.generateToken("test@example.com", "USER")).thenReturn("new-jwt-token");

    // When
    UserService.AuthResult result = userService.refreshToken("old-refresh-token");

    // Then
    assertThat(result.token()).isEqualTo("new-jwt-token");
    assertThat(result.refreshToken()).isEqualTo("old-refresh-token");
    assertThat(result.email()).isEqualTo("test@example.com");
    assertThat(result.role()).isEqualTo("USER");
  }

  @Test
  @TracesRequirement("REQ-AUTH-011")
  @DisplayName("Should resolve ADMIN role and claim for emails in the admin list")
  void shouldResolveAdminRoleFromList() {
    // Given
    when(adminEmailPort.isAdminEmail("admin@example.com")).thenReturn(true);
    when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
    when(idGeneratorPort.generateId()).thenReturn("userAdmin");
    when(passwordEncoder.encode("password123")).thenReturn("encodedPass");
    when(tokenPort.generateToken("admin@example.com", "ADMIN")).thenReturn("jwt-token");
    when(tokenPort.generateRefreshToken("admin@example.com")).thenReturn("refresh-token");

    // When
    UserService.AuthResult result =
        userService.register("admin@example.com", "Admin", "password123");

    // Then
    assertThat(result.role()).isEqualTo("ADMIN");
    verify(tokenPort).generateToken("admin@example.com", "ADMIN");

    User admin = mock(User.class);
    when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
    when(admin.id()).thenReturn("userAdmin");
    when(admin.email()).thenReturn("admin@example.com");
    when(admin.name()).thenReturn("Admin");

    UserService.AuthResult me = userService.me("admin@example.com");

    assertThat(me.role()).isEqualTo("ADMIN");
    assertThat(me.token()).isNull();
    assertThat(me.refreshToken()).isNull();
  }

  @Test
  @TracesRequirement("REQ-AUTH-012")
  @DisplayName("Should expose the resolved role in register, login and me results")
  void shouldExposeResolvedRoleInAllAuthResultBodies() {
    // Given
    when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.empty());
    when(idGeneratorPort.generateId()).thenReturn("user123");
    when(passwordEncoder.encode("password123")).thenReturn("encodedPass");
    when(tokenPort.generateToken("test@example.com", "USER")).thenReturn("jwt-token");
    when(tokenPort.generateRefreshToken("test@example.com")).thenReturn("refresh-token");

    // When / register
    UserService.AuthResult registered =
        userService.register("test@example.com", "Test User", "password123");

    // Then
    assertThat(registered.role()).isEqualTo("USER");

    // When / login
    User user = mock(User.class);
    when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
    when(user.id()).thenReturn("user123");
    when(user.email()).thenReturn("test@example.com");
    when(user.name()).thenReturn("Test User");
    when(tokenPort.generateToken("test@example.com", "USER")).thenReturn("jwt-token");
    when(tokenPort.generateRefreshToken("test@example.com")).thenReturn("refresh-token");

    UserService.AuthResult logged = userService.login("test@example.com", "password123");
    assertThat(logged.role()).isEqualTo("USER");

    // When / me
    UserService.AuthResult me = userService.me("test@example.com");
    assertThat(me.role()).isEqualTo("USER");
  }

  @Test
  @TracesRequirement("REQ-AUTH-007")
  @DisplayName("Should return identity without tokens from me")
  void shouldReturnIdentityWithoutTokens() {
    // Given
    User user = mock(User.class);
    when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
    when(user.id()).thenReturn("user123");
    when(user.email()).thenReturn("test@example.com");
    when(user.name()).thenReturn("Test User");

    // When
    UserService.AuthResult result = userService.me("test@example.com");

    // Then
    assertThat(result.token()).isNull();
    assertThat(result.refreshToken()).isNull();
    assertThat(result.userId()).isEqualTo("user123");
    assertThat(result.email()).isEqualTo("test@example.com");
    assertThat(result.name()).isEqualTo("Test User");
    assertThat(result.role()).isEqualTo("USER");
  }

  @Test
  @TracesRequirement("REQ-AUTH-003")
  @DisplayName("Should throw exception when refresh token is invalid")
  void shouldThrowWhenRefreshTokenInvalid() {
    // Given
    when(tokenPort.validateToken("invalid-token")).thenReturn(false);

    // When/Then
    assertThatThrownBy(() -> userService.refreshToken("invalid-token"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid refresh token");
  }
}
