package com.example.urlshortener.infra.adapter.output.persistence;

import com.example.urlshortener.core.model.User;
import com.example.urlshortener.core.ports.outgoing.UserRepositoryPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * MongoDB implementation of UserRepositoryPort.
 */
@Repository
public class MongoUserRepository implements UserRepositoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoUserRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public User save(User user) {
        UserEntity entity = toEntity(user);
        UserEntity saved = mongoTemplate.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<User> findById(String id) {
        UserEntity entity = mongoTemplate.findById(id, UserEntity.class);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        Query query = new Query(Criteria.where("email").is(email));
        UserEntity entity = mongoTemplate.findOne(query, UserEntity.class);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        Query query = new Query(Criteria.where("email").is(email));
        return mongoTemplate.exists(query, UserEntity.class);
    }

    @Override
    public void deleteById(String id) {
        Query query = new Query(Criteria.where("_id").is(id));
        mongoTemplate.remove(query, UserEntity.class);
    }

    // Mappers

    private UserEntity toEntity(User user) {
        return new UserEntity(
                user.id(),
                user.email(),
                user.name(),
                user.passwordHash(),
                user.plan(),
                user.status(),
                user.subscriptionStartDate(),
                user.subscriptionEndDate(),
                user.quotaUsage(),
                user.stripeCustomerId(),
                user.stripeSubscriptionId(),
                user.createdAt(),
                user.updatedAt());
    }

    private User toDomain(UserEntity entity) {
        return new User(
                entity.getId(),
                entity.getEmail(),
                entity.getName(),
                entity.getPasswordHash(),
                entity.getPlan(),
                entity.getStatus(),
                entity.getSubscriptionStartDate(),
                entity.getSubscriptionEndDate(),
                entity.getQuotaUsage(),
                entity.getStripeCustomerId(),
                entity.getStripeSubscriptionId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
