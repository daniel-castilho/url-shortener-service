package com.example.urlshortener.core.service;

import com.example.urlshortener.core.model.User;
import com.example.urlshortener.core.ports.outgoing.IdGeneratorPort;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.AuthResponse;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.LoginRequest;
import com.example.urlshortener.infra.adapter.input.rest.dto.auth.RegisterRequest;
import com.example.urlshortener.infra.adapter.output.persistence.MongoUserRepository;
import com.example.urlshortener.infra.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

        private final MongoUserRepository userRepository;
        private final PasswordEncoder passwordEncoder;
        private final JwtTokenProvider tokenProvider;
        private final AuthenticationManager authenticationManager;
        private final IdGeneratorPort idGeneratorPort;

        public AuthResponse register(RegisterRequest request) {
                if (userRepository.findByEmail(request.getEmail()).isPresent()) {
                        throw new IllegalArgumentException("Email already in use");
                }

                String userId = idGeneratorPort.generateId(); // Using same generator as URLs for now, or could use UUID

                User user = User.createFreeUser(
                                userId,
                                request.getEmail(),
                                request.getName(),
                                passwordEncoder.encode(request.getPassword()));

                userRepository.save(user);

                String token = tokenProvider.generateToken(user.email());
                String refreshToken = tokenProvider.generateRefreshToken(user.email());

                return AuthResponse.builder()
                                .token(token)
                                .refreshToken(refreshToken)
                                .userId(user.id())
                                .email(user.email())
                                .name(user.name())
                                .build();
        }

        public AuthResponse login(LoginRequest request) {
                Authentication authentication = authenticationManager.authenticate(
                                new UsernamePasswordAuthenticationToken(
                                                request.getEmail(),
                                                request.getPassword()));

                String token = tokenProvider.generateToken(authentication);
                User user = userRepository.findByEmail(request.getEmail())
                                .orElseThrow(() -> new IllegalArgumentException("User not found"));
                String refreshToken = tokenProvider.generateRefreshToken(user.email());

                return AuthResponse.builder()
                                .token(token)
                                .refreshToken(refreshToken)
                                .userId(user.id())
                                .email(user.email())
                                .name(user.name())
                                .build();
        }

        public AuthResponse refreshToken(
                        com.example.urlshortener.infra.adapter.input.rest.dto.auth.RefreshTokenRequest request) {
                String refreshToken = request.getRefreshToken();

                if (!tokenProvider.validateToken(refreshToken)) {
                        throw new IllegalArgumentException("Invalid refresh token");
                }

                String email = tokenProvider.getUsernameFromToken(refreshToken);
                User user = userRepository.findByEmail(email)
                                .orElseThrow(() -> new IllegalArgumentException("User not found"));

                String newToken = tokenProvider.generateToken(email);
                // Optionally rotate refresh token here
                // String newRefreshToken = tokenProvider.generateRefreshToken(email);

                return AuthResponse.builder()
                                .token(newToken)
                                .refreshToken(refreshToken) // Return same refresh token or new one
                                .userId(user.id())
                                .email(user.email())
                                .name(user.name())
                                .build();
        }
}
