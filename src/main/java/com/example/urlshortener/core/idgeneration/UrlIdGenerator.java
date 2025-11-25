package com.example.urlshortener.core.idgeneration;

/**
 * Interface pública do módulo de geração de IDs de URL.
 * O Core Service depende apenas desta interface, desconhecendo as estratégias
 * internas.
 */
public interface UrlIdGenerator {
    String generateId(String customAlias, String userId);
}
