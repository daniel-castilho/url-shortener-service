package com.example.urlshortener.core.ports.incoming;

public interface GetUrlUseCase {
    String getOriginalUrl(String id);
}
