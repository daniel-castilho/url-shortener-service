package ca.tyny.urlshortener.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.core.exception.DomainAlreadyExistsException;
import ca.tyny.urlshortener.core.exception.DomainNotFoundException;
import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.InvalidDomainException;
import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.VerificationTokenPort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CustomDomainService — unit tests")
class CustomDomainServiceTest {

  private final CustomDomainRepositoryPort repository = mock(CustomDomainRepositoryPort.class);
  private final VerificationTokenPort tokenPort = mock(VerificationTokenPort.class);
  private final CustomDomainService service =
      new CustomDomainService(repository, tokenPort, "localhost");

  private static CustomDomain domain(String host, String userId, DomainStatus status) {
    return new CustomDomain(host, userId, status, "url-shortener-verify=deadbeef", Instant.now());
  }

  @Test
  @DisplayName("claim normalizes host, issues token and persists PENDING")
  @TracesRequirement("REQ-SHORT-006")
  void claim_createsPendingWithToken() {
    when(tokenPort.generateToken()).thenReturn("url-shortener-verify=deadbeef");

    CustomDomain result = service.claim("user-1", "LINKS.EXAMPLE.COM.");

    assertThat(result.host()).isEqualTo("links.example.com");
    assertThat(result.userId()).isEqualTo("user-1");
    assertThat(result.status()).isEqualTo(DomainStatus.PENDING);
    assertThat(result.verificationToken()).isEqualTo("url-shortener-verify=deadbeef");
    verify(repository).save(result);
  }

  @Test
  @DisplayName("claim rejects an already-claimed host")
  @TracesRequirement("REQ-SHORT-006")
  void claim_rejectsDuplicate() {
    when(repository.existsByHost("links.example.com")).thenReturn(true);

    assertThatThrownBy(() -> service.claim("user-1", "links.example.com"))
        .isInstanceOf(DomainAlreadyExistsException.class);
  }

  @Test
  @DisplayName("claim rejects the default host")
  @TracesRequirement("REQ-SHORT-006")
  void claim_rejectsDefaultHost() {
    assertThatThrownBy(() -> service.claim("user-1", "localhost"))
        .isInstanceOf(InvalidDomainException.class);
  }

  @Test
  @DisplayName("claim rejects malformed hosts")
  @TracesRequirement("REQ-SHORT-006")
  void claim_rejectsMalformedHosts() {
    assertThatThrownBy(() -> service.claim("user-1", "not a host"))
        .isInstanceOf(InvalidDomainException.class);
    assertThatThrownBy(() -> service.claim("user-1", "singlelabel"))
        .isInstanceOf(InvalidDomainException.class);
    assertThatThrownBy(() -> service.claim("user-1", ""))
        .isInstanceOf(InvalidDomainException.class);
  }

  @Test
  @DisplayName("claim rejects an IP literal as a host")
  @TracesRequirement("REQ-SHORT-006")
  void claim_rejectsIpLiteral() {
    assertThatThrownBy(() -> service.claim("user-1", "192.168.1.1"))
        .isInstanceOf(InvalidDomainException.class);
  }

  @Test
  @DisplayName("list delegates to the repository")
  @TracesRequirement("REQ-SHORT-006")
  void list_returnsOwnDomains() {
    when(repository.findByUserId("user-1"))
        .thenReturn(List.of(domain("links.example.com", "user-1", DomainStatus.PENDING)));

    assertThat(service.listForOwner("user-1")).hasSize(1);
  }

  @Test
  @DisplayName("delete removes the host the caller owns")
  @TracesRequirement("REQ-SHORT-006")
  void delete_removesOwnedHost() {
    when(repository.findByHost("links.example.com"))
        .thenReturn(Optional.of(domain("links.example.com", "user-1", DomainStatus.ACTIVE)));

    service.delete("user-1", "LINKS.EXAMPLE.COM");
    verify(repository).delete("links.example.com");
  }

  @Test
  @DisplayName("delete of another user's domain is forbidden")
  @TracesRequirement("REQ-SHORT-006")
  void delete_foreignDomainIsForbidden() {
    when(repository.findByHost("links.example.com"))
        .thenReturn(Optional.of(domain("links.example.com", "user-2", DomainStatus.ACTIVE)));

    assertThatThrownBy(() -> service.delete("user-1", "links.example.com"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("delete of an unknown host is not found")
  @TracesRequirement("REQ-SHORT-006")
  void delete_unknownHostIsNotFound() {
    when(repository.findByHost("links.example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete("user-1", "links.example.com"))
        .isInstanceOf(DomainNotFoundException.class);
  }

  @Test
  @DisplayName("requestReVerification issues a fresh token and resets to PENDING")
  @TracesRequirement("REQ-SHORT-006")
  void requestReVerification_resetsToPending() {
    when(repository.findByHost("links.example.com"))
        .thenReturn(Optional.of(domain("links.example.com", "user-1", DomainStatus.FAILED)));
    when(tokenPort.generateToken()).thenReturn("url-shortener-verify=beefcafe");

    CustomDomain updated = service.requestReVerification("user-1", "links.example.com");

    assertThat(updated.status()).isEqualTo(DomainStatus.PENDING);
    assertThat(updated.verificationToken()).isEqualTo("url-shortener-verify=beefcafe");
    verify(repository).save(updated);
  }
}
