package com.example.urlshortener.infra.adapter.output.persistence;

import com.example.urlshortener.core.model.ShortUrl;
import com.example.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import com.example.urlshortener.infra.adapter.output.persistence.entity.ShortUrlEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

interface SpringDataCassandraUrlRepository extends CrudRepository<ShortUrlEntity, String> {
}

@Repository
public class CassandraUrlRepository implements UrlRepositoryPort {

    private final SpringDataCassandraUrlRepository springDataRepository;

    public CassandraUrlRepository(SpringDataCassandraUrlRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "databaseCb")
    public void save(ShortUrl shortUrl) {
        ShortUrlEntity entity = new ShortUrlEntity(shortUrl.id(), shortUrl.originalUrl(), shortUrl.createdAt());
        springDataRepository.save(entity);
    }

    @Override
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "databaseCb")
    public java.util.Optional<ShortUrl> findById(String id) {
        return springDataRepository.findById(id)
                .map(entity -> new ShortUrl(entity.getId(), entity.getOriginalUrl(), entity.getCreatedAt()));
    }
}
