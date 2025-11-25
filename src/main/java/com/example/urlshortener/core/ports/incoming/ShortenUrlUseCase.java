package com.example.urlshortener.core.ports.incoming;

import com.example.urlshortener.core.model.ShortUrl;

public interface ShortenUrlUseCase {
    ShortUrl shorten(String originalUrl, String customAlias, String userId);

    default ShortUrl shorten(String originalUrl) {
        return shorten(originalUrl, null, null);
    }
}
