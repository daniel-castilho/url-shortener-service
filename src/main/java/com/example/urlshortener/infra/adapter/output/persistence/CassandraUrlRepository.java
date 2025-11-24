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
    public void save(ShortUrl shortUrl) {
        ShortUrlEntity entity = new ShortUrlEntity(shortUrl.id(), shortUrl.originalUrl(), shortUrl.createdAt());
        springDataRepository.save(entity);
    }

    @Override
    public java.util.Optional<ShortUrl> findById(String id) {
        return springDataRepository.findById(id)
                .map(entity -> new ShortUrl(entity.getId(), entity.getOriginalUrl(), entity.getCreatedAt()));
    }
}
