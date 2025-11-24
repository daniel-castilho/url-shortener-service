package com.example.urlshortener.infra.adapter.output.persistence.entity;

import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.LocalDateTime;

@Table("short_urls")
public class ShortUrlEntity {

    @PrimaryKey
    private String id;
    private String originalUrl;
    private LocalDateTime createdAt;

    public ShortUrlEntity() {
    }

    public ShortUrlEntity(String id, String originalUrl, LocalDateTime createdAt) {
        this.id = id;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
    }

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
