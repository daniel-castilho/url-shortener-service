package ca.tyny.urlshortener.infra.adapter.output.persistence;

import ca.tyny.urlshortener.core.exception.DomainAlreadyExistsException;
import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.CustomDomainEntity;
import ca.tyny.urlshortener.infra.adapter.output.persistence.exception.RepositoryException;
import ca.tyny.urlshortener.infra.adapter.output.persistence.mapper.CustomDomainMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB adapter for {@link CustomDomainRepositoryPort}.
 *
 * <p>Host uniqueness is enforced by the unique {@code host} index created in migration V8;
 * a duplicate save surfaces as {@link DuplicateKeyException} and is mapped to the domain
 * exception (never a check-then-put race).
 */
@Repository
public class MongoCustomDomainRepository implements CustomDomainRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(MongoCustomDomainRepository.class);

    private final MongoTemplate mongoTemplate;
    private final CustomDomainMapper mapper;

    public MongoCustomDomainRepository(MongoTemplate mongoTemplate, CustomDomainMapper mapper) {
        this.mongoTemplate = mongoTemplate;
        this.mapper = mapper;
    }

    @Override
    @CircuitBreaker(name = "databaseCb")
    public Optional<CustomDomain> findByHost(String host) {
        try {
            CustomDomainEntity entity = mongoTemplate.findOne(
                    Query.query(Criteria.where("host").is(host)), CustomDomainEntity.class);
            return entity == null ? Optional.empty() : Optional.of(mapper.toDomain(entity));
        } catch (Exception e) {
            log.error("Error finding custom domain by host: {}", host, e);
            throw new RepositoryException("Failed to find custom domain", e);
        }
    }

    @Override
    @CircuitBreaker(name = "databaseCb")
    public List<CustomDomain> findByUserId(String userId) {
        try {
            return mongoTemplate.find(Query.query(Criteria.where("userId").is(userId)),
                    CustomDomainEntity.class).stream().map(mapper::toDomain).toList();
        } catch (Exception e) {
            log.error("Error finding custom domains for userId: {}", userId, e);
            throw new RepositoryException("Failed to find custom domains", e);
        }
    }

    @Override
    @CircuitBreaker(name = "databaseCb")
    public List<CustomDomain> findAll() {
        try {
            return mongoTemplate.findAll(CustomDomainEntity.class).stream()
                    .map(mapper::toDomain).toList();
        } catch (Exception e) {
            log.error("Error listing all custom domains", e);
            throw new RepositoryException("Failed to list custom domains", e);
        }
    }

    @Override
    @CircuitBreaker(name = "databaseCb")
    public boolean existsByHost(String host) {
        try {
            return mongoTemplate.exists(Query.query(Criteria.where("host").is(host)),
                    CustomDomainEntity.class);
        } catch (Exception e) {
            log.error("Error checking custom domain existence: {}", host, e);
            throw new RepositoryException("Failed to check custom domain existence", e);
        }
    }

    @Override
    @CircuitBreaker(name = "databaseCb")
    public void save(CustomDomain domain) {
        try {
            mongoTemplate.save(mapper.toPersistence(domain));
            log.debug("Custom domain saved: {}", domain.host());
        } catch (DuplicateKeyException e) {
            log.warn("Duplicate host claim for: {}", domain.host());
            throw new DomainAlreadyExistsException(domain.host());
        } catch (Exception e) {
            log.error("Error saving custom domain: {}", domain.host(), e);
            throw new RepositoryException("Failed to persist custom domain", e);
        }
    }

    @Override
    @CircuitBreaker(name = "databaseCb")
    public void delete(String host) {
        try {
            mongoTemplate.remove(Query.query(Criteria.where("host").is(host)), CustomDomainEntity.class);
            log.debug("Custom domain deleted: {}", host);
        } catch (Exception e) {
            log.error("Error deleting custom domain: {}", host, e);
            throw new RepositoryException("Failed to delete custom domain", e);
        }
    }
}