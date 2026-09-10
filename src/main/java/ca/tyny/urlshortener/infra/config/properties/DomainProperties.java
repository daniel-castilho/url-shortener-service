package ca.tyny.urlshortener.infra.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Custom-domain configuration, bound from the {@code app.domain} prefix.
 *
 * @param defaultHost the host the service is served on; cannot be claimed as a custom domain and is
 *     the target of the legacy domain-less redirect branch
 * @param dnsVerifyEnabled master switch for the DNS health check job (false in tests)
 * @param dnsVerifyCron cron expression of the scheduled verification job
 * @param dnsVerifyTimeoutMs per-lookup DNS timeout budget used by the JNDI resolver
 * @param verificationPrefix prefix prepended to the random token stored as the TXT record
 */
@ConfigurationProperties(prefix = "app.domain")
public record DomainProperties(
    String defaultHost,
    boolean dnsVerifyEnabled,
    String dnsVerifyCron,
    int dnsVerifyTimeoutMs,
    String verificationPrefix) {}
