package ca.tyny.urlshortener.infra.adapter.input.rest.edge;

import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort;
import ca.tyny.urlshortener.core.validation.Hostnames;
import ca.tyny.urlshortener.infra.config.properties.DomainProperties;
import ca.tyny.urlshortener.infra.config.properties.EdgeProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Edge authorization endpoint ("ask") for on-demand TLS provisioning of custom domains.
 *
 * <p>The production TLS edge (Caddy, see deploy/proxy/Caddyfile) consults this endpoint before
 * provisioning or serving a certificate for an unknown SNI: {@code GET /internal/edge/domain-ask}
 * with the Caddy-contract {@code domain} query parameter (a {@code host} alias is accepted for
 * operators/curl) plus the shared {@code token}. A 200 answer means "this host is ours — the edge
 * may issue and serve a certificate for it"; 404 means "not ours"; 401 means the caller is not the
 * configured edge.
 *
 * <p>Authorization = the configured public host or an ACTIVE custom domain in the registry (the
 * same set the redirect hot path serves, REQ-SHORT-004/006/007), so the edge can never serve a host
 * the data plane would refuse — and the data plane keeps refusing even if a certificate lingers
 * after a domain goes inactive (defense in depth).
 *
 * <p>Security: no JWT — the shared token IS the authentication (fail-closed when {@code
 * app.edge.ask-token} is unset). The endpoint must never leak information beyond host-ownership
 * booleans; token failures are logged without the token value.
 */
@RestController
@RequestMapping("/internal/edge")
@Tag(name = "Edge", description = "TLS-edge integration (not for browsers; shared-token gated)")
public class EdgeDomainAskController {

  private static final Logger log = LoggerFactory.getLogger(EdgeDomainAskController.class);

  private final CustomDomainRegistryPort customDomainRegistry;
  private final DomainProperties domainProperties;
  private final EdgeProperties edgeProperties;

  public EdgeDomainAskController(
      CustomDomainRegistryPort customDomainRegistry,
      DomainProperties domainProperties,
      EdgeProperties edgeProperties) {
    this.customDomainRegistry = customDomainRegistry;
    this.domainProperties = domainProperties;
    this.edgeProperties = edgeProperties;
  }

  @GetMapping("/domain-ask")
  @Operation(
      summary = "May the edge provision/serve TLS for this host?",
      description =
          "Caddy on-demand TLS ask endpoint: 200 = host is servable (the configured public host "
              + "or an ACTIVE custom domain), 404 = not ours, 401 = missing/wrong shared token. "
              + "Fails closed when EDGE_ASK_TOKEN is unset.")
  @ApiResponse(responseCode = "200", description = "Host is servable by this deployment")
  @ApiResponse(responseCode = "401", description = "Missing or wrong shared token")
  @ApiResponse(responseCode = "404", description = "Host is not served by this deployment")
  public ResponseEntity<Void> ask(
      @Parameter(description = "Host from the TLS SNI (Caddy sends 'domain')", required = true)
          @RequestParam(required = false)
          String domain,
      @Parameter(description = "Alias of 'domain' for operators/curl", required = false)
          @RequestParam(required = false)
          String host,
      @Parameter(hidden = true) @RequestParam(required = false) String token) {
    String askToken = edgeProperties.askToken();
    if (askToken == null || askToken.isBlank()) {
      log.warn("Edge ask endpoint called but EDGE_ASK_TOKEN is not configured — denying");
      return ResponseEntity.status(401).build();
    }
    if (token == null || !constantTimeEquals(token, askToken)) {
      log.warn("Edge ask endpoint called with a missing/wrong token — denying");
      return ResponseEntity.status(401).build();
    }

    String candidate = Hostnames.normalize(domain != null ? domain : host);
    if (candidate == null || candidate.isBlank()) {
      return ResponseEntity.notFound().build();
    }

    String defaultHost = Hostnames.normalize(domainProperties.defaultHost());
    if (candidate.equals(defaultHost) || customDomainRegistry.isActiveHost(candidate)) {
      return ResponseEntity.ok().build();
    }
    return ResponseEntity.notFound().build();
  }

  private static boolean constantTimeEquals(String a, String b) {
    return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
  }
}
