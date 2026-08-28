package ca.tyny.urlshortener.infra.adapter.output.dns;

import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import ca.tyny.urlshortener.core.validation.Hostnames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Performs the actual DNS verification for a custom domain and drives the
 * {@code PENDING → ACTIVE / FAILED} transition.
 *
 * <p>Best-effort by design: a lookup failure is a FAILED check, never an exception that
 * aborts the batch or the scheduled run.
 */
@Component
public class DomainVerificationService {

    private static final Logger log = LoggerFactory.getLogger(DomainVerificationService.class);

    private final CustomDomainRepositoryPort repository;
    private final DnsTxtResolver dnsTxtResolver;
    private final CustomDomainRegistryPort registry;

    public DomainVerificationService(CustomDomainRepositoryPort repository,
            DnsTxtResolver dnsTxtResolver,
            CustomDomainRegistryPort registry) {
        this.repository = repository;
        this.dnsTxtResolver = dnsTxtResolver;
        this.registry = registry;
    }

    /**
     * Verifies {@code host} now: resolves its TXT records, promotes to ACTIVE when the
     * verification token is present, otherwise FAILS the check and removes the host from
     * the active registry.
     *
     * @return the updated domain, or empty when the host is not claimed
     */
    public Optional<CustomDomain> verifyNow(String rawHost) {
        String host = Hostnames.normalize(rawHost);
        if (host == null) {
            return Optional.empty();
        }
        return repository.findByHost(host).map(current -> {
            List<String> txtValues = dnsTxtResolver.resolveTxt(host);
            boolean verified = txtValues.stream()
                    .anyMatch(value -> value.equalsIgnoreCase(current.verificationToken()));
            DomainStatus newStatus = verified ? DomainStatus.ACTIVE : DomainStatus.FAILED;

            CustomDomain updated = current.withStatus(newStatus);
            repository.save(updated);
            if (newStatus == DomainStatus.ACTIVE) {
                registry.markActive(host);
            } else {
                registry.markInactive(host);
            }
            log.info("Custom domain {} verification: {} ({})", host, newStatus,
                    verified ? "token found" : "record missing");
            return updated;
        });
    }
}