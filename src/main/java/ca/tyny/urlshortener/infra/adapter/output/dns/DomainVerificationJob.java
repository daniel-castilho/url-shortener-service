package ca.tyny.urlshortener.infra.adapter.output.dns;

import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import ca.tyny.urlshortener.infra.config.properties.DomainProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled DNS health check for claimed custom domains.
 *
 * <p>Re-verifies every claimed domain (both PENDING/FAILED — promotion, and ACTIVE — revocation
 * detection when the TXT record disappears). Runs via the Spring scheduler and is a no-op when
 * {@code app.domain.dns-verify-enabled} is false (integration tests). Failures for a single domain
 * never abort the run, and the registry is refreshed once the pass completes.
 */
@Component
public class DomainVerificationJob {

  private static final Logger log = LoggerFactory.getLogger(DomainVerificationJob.class);

  private final CustomDomainRepositoryPort repository;
  private final CustomDomainRegistryPort registry;
  private final DomainVerificationService verificationService;
  private final DomainProperties properties;

  public DomainVerificationJob(
      CustomDomainRepositoryPort repository,
      CustomDomainRegistryPort registry,
      DomainVerificationService verificationService,
      DomainProperties properties) {
    this.repository = repository;
    this.registry = registry;
    this.verificationService = verificationService;
    this.properties = properties;
  }

  @Scheduled(cron = "${app.domain.dns-verify-cron}")
  public void verifyAllDomains() {
    if (!properties.dnsVerifyEnabled()) {
      return;
    }
    List<CustomDomain> domains = repository.findAll();
    for (CustomDomain domain : domains) {
      try {
        verificationService.verifyNow(domain.host());
      } catch (Exception e) {
        log.error("Domain verification failed for {}: {}", domain.host(), e.getMessage());
      }
    }
    registry.refresh();
  }
}
