package com.example.urlshortener.infra.adapter.input.rest;

import com.example.urlshortener.config.WithMockSecurity;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.AuthResponse;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.LoginRequest;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@WithMockSecurity
@DisplayName("AuthController Tests")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private com.example.urlshortener.infra.security.JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private com.example.urlshortener.core.service.UserService userService;

    @Test
    @DisplayName("Should register user successfully")
    void shouldRegisterUser() throws Exception {
        // Given
        RegisterRequest request = new RegisterRequest("Test User", "test@example.com", "password123");
        AuthResponse response = new AuthResponse("token", "refresh-token", "id", "test@example.com", "Test User");

        when(userService.register(any(RegisterRequest.class))).thenReturn(response);

        // When/Then
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token"))
                .andExpect(jsonPath("$.email").value("test@example.com"));
    }

    @Test
    @DisplayName("Should login user successfully")
    void shouldLoginUser() throws Exception {
        // Given
        LoginRequest request = new LoginRequest("test@example.com", "password123");
        AuthResponse response = new AuthResponse("token", "refresh-token", "id", "test@example.com", "Test User");

        when(userService.login(any(LoginRequest.class))).thenReturn(response);

        // When/Then
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token"));
    }

    @Test
    @DisplayName("Should validate register request")
    void shouldValidateRegisterRequest() throws Exception {
        // Given - Invalid request (empty fields)
        RegisterRequest request = new RegisterRequest("", "invalid-email", "123");

        // When/Then
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should refresh token successfully")
    void shouldRefreshToken() throws Exception {
        // Given
        com.example.urlshortener.infra.adapter.input.rest.dto.auth.RefreshTokenRequest request = new com.example.urlshortener.infra.adapter.input.rest.dto.auth.RefreshTokenRequest(
                "valid-refresh-token");
        AuthResponse response = new AuthResponse("new-token", "valid-refresh-token", "id", "test@example.com",
                "Test User");

        when(userService.refreshToken(
                any(com.example.urlshortener.infra.adapter.input.rest.dto.auth.RefreshTokenRequest.class)))
                .thenReturn(response);

        // When/Then
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("new-token"));
    }
}
