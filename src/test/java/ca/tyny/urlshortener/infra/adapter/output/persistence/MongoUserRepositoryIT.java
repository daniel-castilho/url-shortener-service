package ca.tyny.urlshortener.infra.adapter.output.persistence;

import ca.tyny.urlshortener.config.BaseIntegrationTest;
import ca.tyny.urlshortener.core.model.QuotaUsage;
import ca.tyny.urlshortener.core.model.SubscriptionPlan;
import ca.tyny.urlshortener.core.model.SubscriptionStatus;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.infra.adapter.output.persistence.UserEntity;
import ca.tyny.urlshortener.infra.adapter.output.persistence.migration.MongoSchemaMigrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MongoUserRepository Integration Tests")
class MongoUserRepositoryIT extends BaseIntegrationTest {

    @Autowired
    private MongoUserRepository mongoUserRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoSchemaMigrator migrator;

    @Test
    @DisplayName("Should save and find user by ID")
    void shouldSaveAndFindById() {
        // Given
        User user = User.createFreeUser("user123", "test@example.com", "Test User", "hashedPassword");

        // When
        mongoUserRepository.save(user);
        Optional<User> foundUser = mongoUserRepository.findById("user123");

        // Then
        assertThat(foundUser).isPresent();
        assertThat(foundUser.get().id()).isEqualTo("user123");
        assertThat(foundUser.get().email()).isEqualTo("test@example.com");
        assertThat(foundUser.get().name()).isEqualTo("Test User");
        assertThat(foundUser.get().plan()).isEqualTo(SubscriptionPlan.FREE);
        assertThat(foundUser.get().quotaUsage()).isNotNull();
        assertThat(foundUser.get().quotaUsage().getVanityUrlsCreatedTotal()).isZero();
    }

    @Test
    @DisplayName("Should find user by Email")
    void shouldFindByEmail() {
        // Given
        User user = User.createFreeUser("user456", "email@example.com", "Email User", "hashedPassword");
        mongoUserRepository.save(user);

        // When
        Optional<User> foundUser = mongoUserRepository.findByEmail("email@example.com");

        // Then
        assertThat(foundUser).isPresent();
        assertThat(foundUser.get().id()).isEqualTo("user456");
        assertThat(foundUser.get().email()).isEqualTo("email@example.com");
    }

    @Test
    @DisplayName("Should return empty when user not found")
    void shouldReturnEmptyWhenNotFound() {
        // When
        Optional<User> resultById = mongoUserRepository.findById("nonexistent");
        Optional<User> resultByEmail = mongoUserRepository.findByEmail("nonexistent@example.com");

        // Then
        assertThat(resultById).isEmpty();
        assertThat(resultByEmail).isEmpty();
    }

    @Test
    @DisplayName("Should persist complex fields correctly")
    void shouldPersistComplexFields() {
        // Given
        QuotaUsage quota = new QuotaUsage();
        quota.setVanityUrlsCreatedThisMonth(10);
        quota.setApiCallsThisMonth(500);

        User complexUser = new User(
                "complexUser", "complex@example.com", "Complex User", "hash",
                SubscriptionPlan.GOLD, SubscriptionStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1),
                quota, "cus_stripe", "sub_stripe",
                LocalDateTime.now(), LocalDateTime.now());

        // When
        mongoUserRepository.save(complexUser);
        Optional<User> foundUser = mongoUserRepository.findById("complexUser");

        // Then
        assertThat(foundUser).isPresent();
        User saved = foundUser.get();

        assertThat(saved.plan()).isEqualTo(SubscriptionPlan.GOLD);
        assertThat(saved.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(saved.stripeCustomerId()).isEqualTo("cus_stripe");
        assertThat(saved.stripeSubscriptionId()).isEqualTo("sub_stripe");

        // Verify Quota Persistence
        assertThat(saved.quotaUsage().getVanityUrlsCreatedThisMonth()).isEqualTo(10);
        assertThat(saved.quotaUsage().getApiCallsThisMonth()).isEqualTo(500);
    }

    @Test
    @DisplayName("Should update existing user")
    void shouldUpdateExistingUser() {
        // Given
        User user = User.createFreeUser("updateUser", "update@example.com", "Original Name", "hash");
        mongoUserRepository.save(user);

        // When
        User updatedUser = new User(
                user.id(), user.email(), "Updated Name", user.passwordHash(),
                SubscriptionPlan.SILVER, SubscriptionStatus.ACTIVE,
                user.subscriptionStartDate(), user.subscriptionEndDate(),
                user.quotaUsage(), user.stripeCustomerId(), user.stripeSubscriptionId(),
                user.createdAt(), LocalDateTime.now());
        mongoUserRepository.save(updatedUser);

        // Then
        Optional<User> found = mongoUserRepository.findById("updateUser");
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("Updated Name");
        assertThat(found.get().plan()).isEqualTo(SubscriptionPlan.SILVER);
    }

    @Test
    @DisplayName("Should enforce storage-level email uniqueness via unique index")
    void shouldEnforceEmailUniquenessAtStorageLevel() {
        // Ensure migrations have run (in case DB was dropped by another test)
        migrator.migrate();

        // Given
        String email = "unique@example.com";
        UserEntity entity1 = new UserEntity(
                "user1", email, "User One", "hash1",
                SubscriptionPlan.FREE, SubscriptionStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1),
                new QuotaUsage(), null, null,
                LocalDateTime.now(), LocalDateTime.now());
        UserEntity entity2 = new UserEntity(
                "user2", email, "User Two", "hash2",
                SubscriptionPlan.FREE, SubscriptionStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1),
                new QuotaUsage(), null, null,
                LocalDateTime.now(), LocalDateTime.now());

        // When - first insert succeeds
        mongoTemplate.insert(entity1);

        // Then - second insert with same email fails at storage level (unique index)
        assertThatThrownBy(() -> mongoTemplate.insert(entity2))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
