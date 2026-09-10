package ca.tyny.urlshortener.infra.adapter.output.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DomainVerificationService — unit tests")
class DomainVerificationServiceTest {

  private final CustomDomainRepositoryPort repository = mock(CustomDomainRepositoryPort.class);
  private final CustomDomainRegistryPort registry = mock(CustomDomainRegistryPort.class);

  private static CustomDomain domain(DomainStatus status, String token) {
    return new CustomDomain("links.example.com", "user-1", status, token, Instant.now());
  }

  @Test
  @DisplayName("token found promotes PENDING to ACTIVE and registers the host")
  void verify_promotesToActive() {
    CustomDomain pending = domain(DomainStatus.PENDING, "url-shortener-verify=deadbeef");
    when(repository.findByHost("links.example.com")).thenReturn(Optional.of(pending));
    DomainVerificationService service =
        new DomainVerificationService(
            repository, host -> List.of("url-shortener-verify=deadbeef", "other-record"), registry);

    CustomDomain result = service.verifyNow("LINKS.EXAMPLE.COM.").orElseThrow();

    assertThat(result.status()).isEqualTo(DomainStatus.ACTIVE);
    verify(repository).save(result);
    verify(registry).markActive("links.example.com");
    verify(registry, never()).markInactive("links.example.com");
  }

  @Test
  @DisplayName("missing record marks the domain FAILED and unregisters the host")
  void verify_marksFailedWhenMissing() {
    CustomDomain active = domain(DomainStatus.ACTIVE, "url-shortener-verify=deadbeef");
    when(repository.findByHost("links.example.com")).thenReturn(Optional.of(active));
    DomainVerificationService service =
        new DomainVerificationService(repository, host -> List.of(), registry);

    CustomDomain result = service.verifyNow("links.example.com").orElseThrow();

    assertThat(result.status()).isEqualTo(DomainStatus.FAILED);
    verify(repository).save(result);
    verify(registry).markInactive("links.example.com");
    verify(registry, never()).markActive("links.example.com");
  }

  @Test
  @DisplayName("lookup failure (empty result) is a failed check, not an exception")
  void verify_handlesLookupFailure() {
    CustomDomain pending = domain(DomainStatus.PENDING, "url-shortener-verify=deadbeef");
    when(repository.findByHost("links.example.com")).thenReturn(Optional.of(pending));
    DomainVerificationService service =
        new DomainVerificationService(repository, host -> List.of(), registry);

    CustomDomain result = service.verifyNow("links.example.com").orElseThrow();

    assertThat(result.status()).isEqualTo(DomainStatus.FAILED);
    verify(registry).markInactive("links.example.com");
  }

  @Test
  @DisplayName("unknown host returns empty and touches nothing")
  void verify_unknownHostIsEmpty() {
    when(repository.findByHost("links.example.com")).thenReturn(Optional.empty());
    DomainVerificationService service =
        new DomainVerificationService(repository, host -> List.of(), registry);

    assertThat(service.verifyNow("links.example.com")).isEmpty();
    verify(registry, never()).markActive("links.example.com");
    verify(registry, never()).markInactive("links.example.com");
  }
}
