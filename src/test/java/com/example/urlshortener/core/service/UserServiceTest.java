package com.example.urlshortener.core.service;

import com.example.urlshortener.core.model.User;
import com.example.urlshortener.core.ports.outgoing.IdGeneratorPort;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.AuthResponse;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.LoginRequest;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.RegisterRequest;
import com.example.urlshortener.infra.adapter.output.persistence.MongoUserRepository;
import com.example.urlshortener.infra.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private MongoUserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider tokenProvider;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private IdGeneratorPort idGeneratorPort;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, tokenProvider, authenticationManager,
                idGeneratorPort);
    }

    @Test
    @DisplayName("Should register new user successfully")
    void shouldRegisterUser() {
        // Given
        RegisterRequest request = new RegisterRequest("Test User", "test@example.com", "password123");
        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.empty());
        when(idGeneratorPort.generateId()).thenReturn("user123");
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encodedPass");
        when(tokenProvider.generateToken(any(String.class))).thenReturn("jwt-token");

        // When
        AuthResponse response = userService.register(request);

        // Then
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getEmail()).isEqualTo(request.getEmail());
        assertThat(response.getName()).isEqualTo(request.getName());

        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Should throw exception when registering existing email")
    void shouldThrowWhenEmailExists() {
        // Given
        RegisterRequest request = new RegisterRequest("Test User", "existing@example.com", "password123");
        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(mock(User.class)));

        // When/Then
        assertThatThrownBy(() -> userService.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email already in use");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should login successfully")
    void shouldLoginUser() {
        // Given
        LoginRequest request = new LoginRequest("test@example.com", "password123");
        Authentication auth = mock(Authentication.class);
        User user = mock(User.class);

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(auth);
        when(tokenProvider.generateToken(auth)).thenReturn("jwt-token");
        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(user));
        when(user.id()).thenReturn("user123");
        when(user.email()).thenReturn(request.getEmail());
        when(user.name()).thenReturn("Test User");

        // When
        AuthResponse response = userService.login(request);

        // Then
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getEmail()).isEqualTo(request.getEmail());
    }
}
