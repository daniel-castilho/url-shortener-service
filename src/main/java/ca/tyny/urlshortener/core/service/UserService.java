package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.model.User;
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

    public UserService(UserRepositoryPort userRepository,
                       PasswordEncoderPort passwordEncoder,
                       TokenPort tokenPort,
                       AuthenticationPort authenticationPort,
                       IdGeneratorPort idGeneratorPort) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenPort = tokenPort;
        this.authenticationPort = authenticationPort;
        this.idGeneratorPort = idGeneratorPort;
    }

    public AuthResult register(String email, String name, String password) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already in use");
        }

        String userId = idGeneratorPort.generateId();

        User user = User.createFreeUser(
                userId,
                email,
                name,
                passwordEncoder.encode(password));

        userRepository.save(user);

        String token = tokenPort.generateToken(user.email());
        String refreshToken = tokenPort.generateRefreshToken(user.email());

        return new AuthResult(token, refreshToken, user.id(), user.email(), user.name());
    }

    public AuthResult login(String email, String password) {
        authenticationPort.authenticate(email, password);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String token = tokenPort.generateToken(user.email());
        String refreshToken = tokenPort.generateRefreshToken(user.email());

        return new AuthResult(token, refreshToken, user.id(), user.email(), user.name());
    }

    public AuthResult refreshToken(String refreshToken) {
        if (!tokenPort.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        String email = tokenPort.getUsernameFromToken(refreshToken);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String newToken = tokenPort.generateToken(email);

        return new AuthResult(newToken, refreshToken, user.id(), user.email(), user.name());
    }

    /**
     * Domain result object for authentication operations.
     */
    public record AuthResult(String token, String refreshToken, String userId, String email, String name) {
    }
}
