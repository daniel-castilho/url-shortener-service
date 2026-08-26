package ca.tyny.urlshortener.infra.adapter.input.rest;

import ca.tyny.urlshortener.core.service.UserService;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.AuthResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.LoginRequest;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.RefreshTokenRequest;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.RegisterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "API for user registration and login")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user", description = "Creates a new user account and returns a JWT token.")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserService.AuthResult result = userService.register(
                request.getEmail(), request.getName(), request.getPassword());
        return ResponseEntity.ok(toAuthResponse(result));
    }

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Authenticates a user and returns a JWT token.")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        UserService.AuthResult result = userService.login(request.getEmail(), request.getPassword());
        return ResponseEntity.ok(toAuthResponse(result));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh Token", description = "Refreshes the access token using a valid refresh token.")
    public ResponseEntity<AuthResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {
        UserService.AuthResult result = userService.refreshToken(request.getRefreshToken());
        return ResponseEntity.ok(toAuthResponse(result));
    }

    private AuthResponse toAuthResponse(UserService.AuthResult result) {
        return AuthResponse.builder()
                .token(result.token())
                .refreshToken(result.refreshToken())
                .userId(result.userId())
                .email(result.email())
                .name(result.name())
                .build();
    }
}
