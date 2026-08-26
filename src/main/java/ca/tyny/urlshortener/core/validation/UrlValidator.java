package ca.tyny.urlshortener.core.validation;

import ca.tyny.urlshortener.core.exception.InvalidDestinationException;
import java.util.List;

/**
 * Validates destination URLs for SSRF protection and format compliance.
 * Implementations can enforce HTTPS, block private/internal IPs, check blocklists, etc.
 */
public interface UrlValidator {

    /**
     * Validates a destination URL for SSRF and format compliance.
     *
     * @param url the raw URL string to validate
     * @throws InvalidDestinationException if the URL fails validation
     */
    void validate(String url) throws InvalidDestinationException;

    /**
     * Result of URL validation.
     */
    record ValidationResult(
            boolean allowed,
            String host,
            List<java.net.InetAddress> resolvedIps,
            String reason
    ) {
        public static ValidationResult allowed(String host, List<java.net.InetAddress> ips) {
            return new ValidationResult(true, host, ips, null);
        }

        public static ValidationResult blocked(String host, List<java.net.InetAddress> ips, String reason) {
            return new ValidationResult(false, host, ips, reason);
        }
    }
}