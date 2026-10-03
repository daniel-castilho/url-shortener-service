package ca.tyny.urlshortener.infra.adapter.input.rest.edge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort;
import ca.tyny.urlshortener.infra.config.properties.DomainProperties;
import ca.tyny.urlshortener.infra.config.properties.EdgeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EdgeDomainAskController.class)
@DisplayName("EdgeDomainAskController (Caddy on-demand TLS ask endpoint)")
class EdgeDomainAskControllerTest {

  private static final String DEFAULT_HOST = "short.example.com";
  private static final String TOKEN = "unit-test-edge-token";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ca.tyny.urlshortener.infra.security.JwtTokenProvider jwtTokenProvider;

  @MockitoBean
  private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

  @MockitoBean private CustomDomainRegistryPort customDomainRegistry;

  @MockitoBean private DomainProperties domainProperties;

  @MockitoBean private EdgeProperties edgeProperties;

  @BeforeEach
  void setUp() {
    when(domainProperties.defaultHost()).thenReturn(DEFAULT_HOST);
    when(edgeProperties.askToken()).thenReturn(TOKEN);
    when(customDomainRegistry.isActiveHost(anyString())).thenReturn(false);
  }

  private String ask(String domain, String host, String token) {
    StringBuilder url = new StringBuilder("/internal/edge/domain-ask?");
    if (domain != null) {
      url.append("domain=").append(domain).append("&");
    }
    if (host != null) {
      url.append("host=").append(host).append("&");
    }
    if (token != null) {
      url.append("token=").append(token);
    }
    return url.toString();
  }

  @Test
  @DisplayName("Unconfigured EDGE_ASK_TOKEN fails closed (401) even with a token param")
  void failsClosedWhenTokenUnset() throws Exception {
    when(edgeProperties.askToken()).thenReturn("");

    mockMvc.perform(get(ask("go.acme.io", null, "anything"))).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Wrong or missing token is denied (401)")
  void deniesWrongOrMissingToken() throws Exception {
    mockMvc
        .perform(get(ask("go.acme.io", null, "wrong-token")))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get(ask("go.acme.io", null, null))).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("ACTIVE custom domain is authorized (200) — Caddy 'domain' param contract")
  void authorizesActiveCustomDomain() throws Exception {
    when(customDomainRegistry.isActiveHost("go.acme.io")).thenReturn(true);

    mockMvc.perform(get(ask("go.acme.io", null, TOKEN))).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Host is normalized before the registry lookup")
  void normalizesHostBeforeLookup() throws Exception {
    when(customDomainRegistry.isActiveHost("go.acme.io")).thenReturn(true);

    mockMvc.perform(get(ask("GO.ACME.IO.", null, TOKEN))).andExpect(status().isOk());
  }

  @Test
  @DisplayName("The configured public host is always authorized (200)")
  void authorizesDefaultHost() throws Exception {
    mockMvc.perform(get(ask(DEFAULT_HOST, null, TOKEN))).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Unknown host is not ours (404)")
  void deniesUnknownHost() throws Exception {
    mockMvc.perform(get(ask("not-ours.example.com", null, TOKEN))).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("The 'host' alias works for operators/curl")
  void hostAliasAccepted() throws Exception {
    when(customDomainRegistry.isActiveHost(any())).thenReturn(true);

    mockMvc.perform(get(ask(null, "go.acme.io", TOKEN))).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Neither domain nor host present is a deny (404)")
  void deniesMissingHost() throws Exception {
    mockMvc.perform(get(ask(null, null, TOKEN))).andExpect(status().isNotFound());
  }
}
