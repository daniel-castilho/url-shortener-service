package ca.tyny.urlshortener.infra.adapter.output.persistence.entity;

import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Entidade de persistência que mapeia uma URL encurtada para o MongoDB.
 *
 * Esta classe é responsável por representar a estrutura de dados conforme
 * armazenada no banco de dados MongoDB. Não deve conter lógica de negócio,
 * apenas dados e suas anotações de mapeamento.
 *
 * Conversão:
 * - Domain Model (ShortUrl) ↔ Mapper ↔ Persistence Entity (ShortUrlEntity)
 *
 * @author Migration from Cassandra to MongoDB
 */
@Document(collection = MongoCollections.SHORT_URLS)
public class ShortUrlEntity {

    /**
     * Identificador único da URL encurtada (chave primária).
     * Gerado pelo domínio usando Base62 (SecureRandom).
     */
    @Id
    private String id;

    /**
     * URL original completa que foi encurtada.
     * No unique index — the same URL may be shortened multiple times (no dedup).
     */
    private String originalUrl;

    /**
     * SHA-256 hash of the original URL (lowercase hex).
     * Stored for future analytics; no unique index on this field.
     */
    private String urlHash;

    /**
     * Timestamp de quando a URL foi encurtada.
     */
    private LocalDateTime createdAt;

    @Indexed
    private String userId;

    private boolean isCustomAlias;

    /**
     * Construtor sem argumentos necessário para desserialização do MongoDB.
     */
    public ShortUrlEntity() {
    }

    /**
     * Construtor com todos os campos.
     *
     * @param id          identificador único da URL encurtada
     * @param originalUrl URL original a ser armazenada
     * @param createdAt   data/hora de criação do encurtamento
     */
    public ShortUrlEntity(String id, String originalUrl, String urlHash, LocalDateTime createdAt, String userId,
            boolean isCustomAlias) {
        this.id = id;
        this.originalUrl = originalUrl;
        this.urlHash = urlHash;
        this.createdAt = createdAt;
        this.userId = userId;
        this.isCustomAlias = isCustomAlias;
    }

    // ...existing code...
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    public String getUrlHash() {
        return urlHash;
    }

    public void setUrlHash(String urlHash) {
        this.urlHash = urlHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public boolean isCustomAlias() {
        return isCustomAlias;
    }

    public void setCustomAlias(boolean customAlias) {
        isCustomAlias = customAlias;
    }
}
