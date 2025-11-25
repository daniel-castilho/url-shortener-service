package com.example.urlshortener.core.idgeneration;

public interface UrlIdGenerationStrategy {
    /**
     * Verifica se esta estratégia suporta o cenário atual.
     */
    boolean supports(String customAlias);

    /**
     * Gera (ou valida e retorna) o ID da URL.
     */
    String generateId(String customAlias, String userId);
}
