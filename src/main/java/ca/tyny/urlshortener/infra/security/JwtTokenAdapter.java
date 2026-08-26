package ca.tyny.urlshortener.infra.security;

import ca.tyny.urlshortener.core.ports.outgoing.TokenPort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenAdapter implements TokenPort {

    private final JwtTokenProvider jwtTokenProvider;

    public JwtTokenAdapter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public String generateToken(String email) {
        return jwtTokenProvider.generateToken(email);
    }

    @Override
    public String generateRefreshToken(String email) {
        return jwtTokenProvider.generateRefreshToken(email);
    }

    @Override
    public boolean validateToken(String token) {
        return jwtTokenProvider.validateToken(token);
    }

    @Override
    public String getUsernameFromToken(String token) {
        return jwtTokenProvider.getUsernameFromToken(token);
    }
}
