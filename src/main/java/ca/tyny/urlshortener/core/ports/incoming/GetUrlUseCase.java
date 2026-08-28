package ca.tyny.urlshortener.core.ports.incoming;

/**
 * Inbound port for the redirect (hot) path.
 *
 * <p>Resolution is host-aware (strict mirror): a link is only served on the host it was
 * bound to — {@code domain == null} links only on the default host, custom-domain links
 * only under their verified host. Unknown hosts resolve nothing.
 */
public interface GetUrlUseCase {

    /**
     * Resolves {@code id} to its destination URL for the given request host.
     *
     * @param host raw {@code Host} header value (port and case tolerated; null/blank
     *             treated as the default host)
     * @param id   short code
     * @return the destination URL
     */
    String getOriginalUrl(String host, String id);
}