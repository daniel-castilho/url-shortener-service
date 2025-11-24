package com.example.urlshortener.core.ports.outgoing;

public interface UrlCachePort {
    String get(String id);

    void put(String id, String originalUrl);
}
