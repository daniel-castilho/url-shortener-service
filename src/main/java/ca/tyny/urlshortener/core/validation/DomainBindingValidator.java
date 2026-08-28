package ca.tyny.urlshortener.core.validation;

import ca.tyny.urlshortener.core.exception.DomainNotVerifiedException;
import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.InvalidDomainException;
import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;

/**
 * Validates that the given user may bind a link to the requested custom domain.
 *
 * <p>A domain binding is allowed only for the domain owner with an {@link DomainStatus#ACTIVE}
 * (verified) domain. Unclaimed hosts, other people's domains and not-yet-verified domains are
 * rejected so links can never be shortened under a host the caller does not control.
 */
public final class DomainBindingValidator {

    private DomainBindingValidator() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Resolves the normalized domain a caller may bind a link to, or {@code null} to stay
     * default-host bound.
     *
     * @param requestedDomain raw requested domain; blank/null means "default host"
     * @return the normalized, verified host the caller owns
     * @throws IllegalArgumentException when authentication is required but missing
     * @throws InvalidDomainException when the host is not claimed or is malformed
     * @throws ForbiddenException when the domain belongs to another user
     * @throws DomainNotVerifiedException when the domain is not verified/active
     */
    public static String bindableDomain(CustomDomainRepositoryPort repository,
            String userId, String requestedDomain) {
        if (requestedDomain == null || requestedDomain.isBlank()) {
            return null;
        }
        if (userId == null) {
            throw new IllegalArgumentException("Authentication required for custom domains");
        }

        String normalized = Hostnames.normalize(requestedDomain);
        if (normalized == null || normalized.isBlank()) {
            throw new InvalidDomainException("Invalid domain: " + requestedDomain);
        }
        Hostnames.validate(normalized);

        CustomDomain domain = repository.findByHost(normalized)
                .orElseThrow(() -> new InvalidDomainException("Domain is not claimed: " + normalized));

        if (!domain.userId().equals(userId)) {
            throw new ForbiddenException("You do not own the custom domain: " + normalized);
        }
        if (domain.status() != DomainStatus.ACTIVE) {
            throw new DomainNotVerifiedException(normalized);
        }
        return normalized;
    }
}