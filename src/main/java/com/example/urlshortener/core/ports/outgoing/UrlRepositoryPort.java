package com.example.urlshortener.core.ports.outgoing;

import com.example.urlshortener.core.model.ShortUrl;

public interface UrlRepositoryPort {
    void save(ShortUrl shortUrl);

    java.util.Optional<ShortUrl> findById(String id);
}
