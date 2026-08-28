package ca.tyny.urlshortener.infra.adapter.output.dns;

import java.util.List;

/**
 * Resolves the TXT records of a host for custom-domain DNS verification.
 *
 * <p>Abstracted so integration tests can inject a stub (real DNS is not available in
 * Testcontainers) while production uses the JDK JNDI resolver — no external dependency.
 */
@FunctionalInterface
public interface DnsTxtResolver {

    /**
     * Returns the TXT record values of {@code host}.
     *
     * @param host lower-case host to look up
     * @return the raw TXT values (quotes stripped); empty when the record is absent or the
     *         lookup fails (missing name, timeout, etc.)
     */
    List<String> resolveTxt(String host);
}