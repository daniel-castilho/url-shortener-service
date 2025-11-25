package com.example.urlshortener.infra.adapter.input.rest;

import com.example.urlshortener.core.service.UserService;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.AuthResponse;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.LoginRequest;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.RegisterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "API for user registration and login")
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    @Operation(summary = "Register a new user", description = "Creates a new user account and returns a JWT token.")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Authenticates a user and returns a JWT token.")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh Token", description = "Refreshes the access token using a valid refresh token.")
    public ResponseEntity<AuthResponse> refresh(
            @Valid @RequestBody com.example.urlshortener.infra.adapter.input.rest.dto.auth.RefreshTokenRequest request) {
        return ResponseEntity.ok(userService.refreshToken(request));
    }
}
