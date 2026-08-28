package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.DomainAlreadyExistsException;
import ca.tyny.urlshortener.core.exception.DomainNotFoundException;
import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.InvalidDomainException;
import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.ports.incoming.CustomDomainUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.VerificationTokenPort;
import ca.tyny.urlshortener.core.validation.Hostnames;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Use-case orchestration for custom-domain management.
 *
 * <p>Domain claims are unique by host, owner-guarded, and start in PENDING until the DNS
 * health check (infra-side) promotes them to ACTIVE. Pure domain logic — no framework
 * imports.
 */
public class CustomDomainService implements CustomDomainUseCase {

    private final CustomDomainRepositoryPort repository;
    private final VerificationTokenPort verificationToken;
    private final String defaultHost;

    public CustomDomainService(CustomDomainRepositoryPort repository,
            VerificationTokenPort verificationToken,
            String defaultHost) {
        this.repository = repository;
        this.verificationToken = verificationToken;
        this.defaultHost = defaultHost;
    }

    @Override
    public CustomDomain claim(String userId, String host) {
        Objects.requireNonNull(userId, "userId cannot be null");
        String normalized = Hostnames.normalize(host);
        Hostnames.validate(normalized);

        if (normalized.equalsIgnoreCase(defaultHost)) {
            throw new InvalidDomainException("The default host cannot be claimed as a custom domain: " + normalized);
        }
        if (repository.existsByHost(normalized)) {
            throw new DomainAlreadyExistsException(normalized);
        }

        CustomDomain domain = new CustomDomain(normalized, userId, DomainStatus.PENDING,
                verificationToken.generateToken(), Instant.now());
        repository.save(domain);
        return domain;
    }

    @Override
    public List<CustomDomain> listForOwner(String userId) {
        Objects.requireNonNull(userId, "userId cannot be null");
        return repository.findByUserId(userId);
    }

    @Override
    public void delete(String userId, String host) {
        Objects.requireNonNull(userId, "userId cannot be null");
        CustomDomain domain = requireOwned(userId, host);
        repository.delete(domain.host());
    }

    @Override
    public CustomDomain requestReVerification(String userId, String host) {
        Objects.requireNonNull(userId, "userId cannot be null");
        CustomDomain domain = requireOwned(userId, host);
        CustomDomain updated = domain.withVerificationToken(verificationToken.generateToken())
                .withStatus(DomainStatus.PENDING);
        repository.save(updated);
        return updated;
    }

    private CustomDomain requireOwned(String userId, String host) {
        String normalized = Hostnames.normalize(host);
        CustomDomain domain = repository.findByHost(normalized)
                .orElseThrow(() -> new DomainNotFoundException(normalized));
        if (!domain.userId().equals(userId)) {
            throw new ForbiddenException("You do not own the custom domain: " + normalized);
        }
        return domain;
    }
}