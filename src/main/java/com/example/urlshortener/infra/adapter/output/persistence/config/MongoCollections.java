package com.example.urlshortener.infra.adapter.output.persistence.config;

/**
 * Centraliza constantes de nomes de collections MongoDB.
 * Evita magic strings espalhadas pelo código (violação de DRY principle).
 *
 * Facilita refatoração futura se nomes de collections precisarem mudar.
 */
public class MongoCollections {

    /**
     * Nome da collection que armazena as URLs encurtadas.
     * Utilizada para mapeamento de entidades via @Document(collection = MongoCollections.SHORT_URLS)
     */
    public static final String SHORT_URLS = "short_urls";

    // Prevent instantiation
    private MongoCollections() {
        throw new AssertionError("Utility class should not be instantiated");
    }
}

