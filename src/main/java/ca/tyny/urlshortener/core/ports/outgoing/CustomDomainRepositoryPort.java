package ca.tyny.urlshortener.core.ports.outgoing;

import ca.tyny.urlshortener.core.exception.DomainAlreadyExistsException;
import ca.tyny.urlshortener.core.model.CustomDomain;

import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for claimed custom domains.
 *
 * <p>Owned by {@code core/}; implemented by the Mongo adapter. Used by domain
 * management (claim/list/delete/verify), by the shorten/PATCH domain validation, and by
 * the redirect host-aware resolution (via the active-host registry cache).
 */
public interface CustomDomainRepositoryPort {

    Optional<CustomDomain> findByHost(String host);

    List<CustomDomain> findByUserId(String userId);

    List<CustomDomain> findAll();

    boolean existsByHost(String host);

    /**
     * Persists a domain claim (or an updated claim).
     *
     * @param domain the domain to persist
     * @throws DomainAlreadyExistsException when the host is already claimed
     *         (concurrent claim duplicate)
     */
    void save(CustomDomain domain);

    /** Removes the claim for the given host. No-op when the host is not claimed. */
    void delete(String host);
}