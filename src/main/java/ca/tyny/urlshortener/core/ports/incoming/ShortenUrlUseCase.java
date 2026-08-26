package ca.tyny.urlshortener.core.ports.incoming;

import ca.tyny.urlshortener.core.model.ShortUrl;

public interface ShortenUrlUseCase {
    ShortUrl shorten(String originalUrl, String customAlias, String userId);

    default ShortUrl shorten(String originalUrl) {
        return shorten(originalUrl, null, null);
    }
}
