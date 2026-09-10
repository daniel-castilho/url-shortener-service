package ca.tyny.urlshortener.infra.adapter.input.rest;

import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.ports.incoming.CustomDomainUseCase;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ClaimDomainRequest;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.DomainListResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.DomainResponse;
import ca.tyny.urlshortener.infra.adapter.output.dns.DomainVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for the custom-domain management use case.
 *
 * <p>Authenticated and owner-scoped: a caller only ever sees/manages their own claims. The DNS
 * verification token is returned on creation so the client can publish the TXT record.
 */
@RestController
@RequestMapping("/api/v1/domains")
@Tag(
    name = "Custom Domains",
    description = "Claim and manage branded domains (authenticated, owner-scoped)")
public class DomainController {

  private final CustomDomainUseCase customDomainUseCase;
  private final DomainVerificationService verificationService;
  private final CurrentUserResolver currentUser;

  public DomainController(
      CustomDomainUseCase customDomainUseCase,
      DomainVerificationService verificationService,
      CurrentUserResolver currentUser) {
    this.customDomainUseCase = customDomainUseCase;
    this.verificationService = verificationService;
    this.currentUser = currentUser;
  }

  @PostMapping
  @Operation(
      summary = "Claim a custom domain",
      description =
          "Claims a host for the caller (PENDING) and returns the DNS verification token to publish as a TXT record.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Domain claimed, token issued"),
        @ApiResponse(responseCode = "400", description = "Invalid host"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "409", description = "Host already claimed")
      })
  public ResponseEntity<DomainResponse> claim(@Valid @RequestBody ClaimDomainRequest request) {
    String userId = currentUser.resolveUserId();
    CustomDomain domain = customDomainUseCase.claim(userId, request.host());
    return ResponseEntity.status(HttpStatus.CREATED).body(DomainResponse.from(domain));
  }

  @GetMapping
  @Operation(
      summary = "List claimed domains",
      description = "Returns the caller's custom domains with their verification status.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "List of claimed domains"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated")
      })
  public ResponseEntity<DomainListResponse> list() {
    String userId = currentUser.resolveUserId();
    List<DomainResponse> domains =
        customDomainUseCase.listForOwner(userId).stream().map(DomainResponse::from).toList();
    return ResponseEntity.ok(new DomainListResponse(domains));
  }

  @PostMapping("/{host}/verify")
  @Operation(
      summary = "Re-trigger DNS verification",
      description =
          "Issues a fresh token, resets the domain to PENDING and runs an immediate DNS check against it.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Verification re-triggered, result returned"),
        @ApiResponse(responseCode = "400", description = "Invalid host"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Not the owner"),
        @ApiResponse(responseCode = "404", description = "Domain not found")
      })
  public ResponseEntity<DomainResponse> verify(
      @Parameter(description = "Claimed host", example = "links.example.com") @PathVariable
          String host) {
    String userId = currentUser.resolveUserId();
    CustomDomain domain = customDomainUseCase.requestReVerification(userId, host);
    CustomDomain checked = verificationService.verifyNow(domain.host()).orElse(domain);
    return ResponseEntity.ok(DomainResponse.from(checked));
  }

  @DeleteMapping("/{host}")
  @Operation(
      summary = "Remove a custom domain",
      description =
          "Drops the caller's claim; links configured with the host fall back to the default-host branch.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Domain removed"),
        @ApiResponse(responseCode = "400", description = "Invalid host"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Not the owner"),
        @ApiResponse(responseCode = "404", description = "Domain not found")
      })
  public ResponseEntity<Void> delete(
      @Parameter(description = "Claimed host", example = "links.example.com") @PathVariable
          String host) {
    String userId = currentUser.resolveUserId();
    customDomainUseCase.delete(userId, host);
    return ResponseEntity.noContent().build();
  }
}
