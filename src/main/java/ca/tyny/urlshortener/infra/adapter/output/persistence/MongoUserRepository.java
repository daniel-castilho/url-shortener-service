package ca.tyny.urlshortener.infra.adapter.output.persistence;

import ca.tyny.urlshortener.core.model.Cursor;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** MongoDB implementation of UserRepositoryPort. */
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

  /**
   * Atomically increments the vanity-URL quota counters (monthly + total) using $inc on the
   * embedded quotaUsage document. Concurrent increments are never lost; a missing user is a no-op.
   */
  @Override
  public void incrementVanityUsage(String id) {
    org.springframework.data.mongodb.core.query.Update update =
        new org.springframework.data.mongodb.core.query.Update()
            .inc("quotaUsage.vanityUrlsCreatedThisMonth", 1)
            .inc("quotaUsage.vanityUrlsCreatedTotal", 1);
    mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(id)), update, UserEntity.class);
  }

  /**
   * Returns a cursor-paginated page of users ordered by {@code createdAt DESC, id DESC} (stable,
   * same contract as the links list). Uses the same Cursor shape ({@code epochMillis:id}) and
   * fetches {@code limit + 1} to detect {@code hasMore}.
   */
  @Override
  public PageResult<User> findPage(Cursor cursor, int limit) {
    return findPageWithCriteria(null, cursor, limit);
  }

  /**
   * Same as {@link #findPage(Cursor, int)} but restricted to users whose email starts with the
   * given prefix. The prefix is regex-escaped ({@link Pattern#quote}) so characters like {@code $}
   * or {@code .} never break the query.
   */
  @Override
  public PageResult<User> findPageByEmailPrefix(String emailPrefix, Cursor cursor, int limit) {
    Criteria prefixCriteria = null;
    if (emailPrefix != null && !emailPrefix.isBlank()) {
      prefixCriteria =
          Criteria.where("email").regex("^" + Pattern.quote(emailPrefix.trim()) + ".*", "i");
    }
    return findPageWithCriteria(prefixCriteria, cursor, limit);
  }

  /**
   * Sets (or clears) the {@code blocked} flag with a targeted {@code updateOne} — expand-only, no
   * full-document rewrite, no touching of any other field. Idempotent for missing users (no-op).
   */
  @Override
  public void setBlocked(String id, boolean blocked) {
    Update update = new Update().set("blocked", blocked);
    mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(id)), update, UserEntity.class);
  }

  private PageResult<User> findPageWithCriteria(Criteria extra, Cursor cursor, int limit) {
    if (limit > 100) {
      limit = 100;
    }
    Query query = new Query().with(Sort.by(Sort.Direction.DESC, "createdAt", "_id"));
    if (extra != null) {
      query.addCriteria(extra);
    }
    if (cursor != null) {
      long createdAtMillis = cursor.createdAtEpochMillis();
      String cursorId = cursor.id();
      java.time.LocalDateTime cursorCreatedAt =
          java.time.Instant.ofEpochMilli(createdAtMillis)
              .atZone(java.time.ZoneOffset.UTC)
              .toLocalDateTime();
      query.addCriteria(
          new Criteria()
              .orOperator(
                  Criteria.where("createdAt").lt(cursorCreatedAt),
                  new Criteria()
                      .andOperator(
                          Criteria.where("createdAt").is(cursorCreatedAt),
                          Criteria.where("_id").lt(cursorId))));
    }
    query.limit(limit + 1);
    java.util.List<UserEntity> entities = mongoTemplate.find(query, UserEntity.class);
    boolean hasMore = entities.size() > limit;
    if (hasMore) {
      entities = entities.subList(0, limit);
    }
    java.util.List<User> items = entities.stream().map(this::toDomain).toList();
    Cursor nextCursor =
        hasMore && !items.isEmpty()
            ? Cursor.of(
                items.getLast().createdAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
                items.getLast().id())
            : null;
    return PageResult.of(items, nextCursor);
  }

  // Mappers

  private UserEntity toEntity(User user) {
    return new UserEntity(
        user.id(),
        user.email(),
        user.name(),
        user.blocked(),
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
        entity.isBlocked(),
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
