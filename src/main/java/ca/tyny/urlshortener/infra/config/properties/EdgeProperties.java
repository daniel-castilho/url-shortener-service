package ca.tyny.urlshortener.infra.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Edge integration configuration, bound from the {@code app.edge} prefix (see application.yaml).
 *
 * @param askToken shared secret between the TLS edge and the {@code /internal/edge/domain-ask}
 *     endpoint. The edge (Caddy on-demand TLS) presents it on every ask call; the endpoint is the
 *     gate that decides whether the edge may provision/serve a certificate for a host. Empty
 *     (default) means the endpoint fails closed — the edge can never provision custom-domain
 *     certificates until an operator sets it.
 */
@ConfigurationProperties(prefix = "app.edge")
public record EdgeProperties(@DefaultValue("") String askToken) {}
