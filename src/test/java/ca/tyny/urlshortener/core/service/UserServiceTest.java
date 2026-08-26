package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.outgoing.AuthenticationPort;
import ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort;
import ca.tyny.urlshortener.core.ports.outgoing.PasswordEncoderPort;
import ca.tyny.urlshortener.core.ports.outgoing.TokenPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepositoryPort userRepository;
    @Mock
    private PasswordEncoderPort passwordEncoder;
    @Mock
    private TokenPort tokenPort;
    @Mock
    private AuthenticationPort authenticationPort;
    @Mock
    private IdGeneratorPort idGeneratorPort;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, tokenPort, authenticationPort, idGeneratorPort);
    }

    @Test
    @DisplayName("Should register new user successfully")
    void shouldRegisterUser() {
        // Given
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.empty());
        when(idGeneratorPort.generateId()).thenReturn("user123");
        when(passwordEncoder.encode("password123")).thenReturn("encodedPass");
        when(tokenPort.generateToken("test@example.com")).thenReturn("jwt-token");
        when(tokenPort.generateRefreshToken("test@example.com")).thenReturn("refresh-token");

        // When
        UserService.AuthResult result = userService.register("test@example.com", "Test User", "password123");

        // Then
        assertThat(result.token()).isEqualTo("jwt-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.email()).isEqualTo("test@example.com");
        assertThat(result.name()).isEqualTo("Test User");
        assertThat(result.userId()).isEqualTo("user123");

        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Should throw exception when registering existing email")
    void shouldThrowWhenEmailExists() {
        // Given
        when(userRepository.findByEmail("existing@example.com")).thenReturn(Optional.of(mock(User.class)));

        // When/Then
        assertThatThrownBy(() -> userService.register("existing@example.com", "Test User", "password123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email already in use");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should login successfully")
    void shouldLoginUser() {
        // Given
        User user = mock(User.class);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(user.id()).thenReturn("user123");
        when(user.email()).thenReturn("test@example.com");
        when(user.name()).thenReturn("Test User");
        when(tokenPort.generateToken("test@example.com")).thenReturn("jwt-token");
        when(tokenPort.generateRefreshToken("test@example.com")).thenReturn("refresh-token");

        // When
        UserService.AuthResult result = userService.login("test@example.com", "password123");

        // Then
        assertThat(result.token()).isEqualTo("jwt-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.email()).isEqualTo("test@example.com");

        verify(authenticationPort).authenticate("test@example.com", "password123");
    }

    @Test
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
        when(tokenPort.generateToken("test@example.com")).thenReturn("new-jwt-token");

        // When
        UserService.AuthResult result = userService.refreshToken("old-refresh-token");

        // Then
        assertThat(result.token()).isEqualTo("new-jwt-token");
        assertThat(result.refreshToken()).isEqualTo("old-refresh-token");
        assertThat(result.email()).isEqualTo("test@example.com");
    }

    @Test
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
