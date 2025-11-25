package com.example.urlshortener.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("User Domain Model Tests")
class UserTest {

    @Test
    @DisplayName("Should create FREE user with default values")
    void shouldCreateFreeUser() {
        // When
        User user = User.createFreeUser("user123", "test@example.com", "Test User", "hashedPassword");

        // Then
        assertThat(user.id()).isEqualTo("user123");
        assertThat(user.email()).isEqualTo("test@example.com");
        assertThat(user.name()).isEqualTo("Test User");
        assertThat(user.passwordHash()).isEqualTo("hashedPassword");
        assertThat(user.plan()).isEqualTo(SubscriptionPlan.FREE);
        assertThat(user.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(user.quotaUsage()).isNotNull();
        assertThat(user.quotaUsage().getVanityUrlsCreatedTotal()).isZero();
        assertThat(user.stripeCustomerId()).isNull();
        assertThat(user.stripeSubscriptionId()).isNull();
        assertThat(user.createdAt()).isNotNull();
        assertThat(user.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should identify active subscription correctly")
    void shouldIdentifyActiveSubscription() {
        // Given - ACTIVE status
        User activeUser = new User(
                "user1", "test@example.com", "Test", "hash",
                SubscriptionPlan.GOLD, SubscriptionStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1),
                new QuotaUsage(), null, null,
                LocalDateTime.now(), LocalDateTime.now());

        // Given - TRIAL status
        User trialUser = new User(
                "user2", "test@example.com", "Test", "hash",
                SubscriptionPlan.SILVER, SubscriptionStatus.TRIAL,
                LocalDateTime.now(), LocalDateTime.now().plusDays(7),
                new QuotaUsage(), null, null,
                LocalDateTime.now(), LocalDateTime.now());

        // Given - CANCELED status
        User canceledUser = new User(
                "user3", "test@example.com", "Test", "hash",
                SubscriptionPlan.FREE, SubscriptionStatus.CANCELED,
                LocalDateTime.now(), LocalDateTime.now().minusDays(1),
                new QuotaUsage(), null, null,
                LocalDateTime.now(), LocalDateTime.now());

        // Then
        assertThat(activeUser.hasActiveSubscription()).isTrue();
        assertThat(trialUser.hasActiveSubscription()).isTrue();
        assertThat(canceledUser.hasActiveSubscription()).isFalse();
    }

    @Test
    @DisplayName("Should identify paid plans correctly")
    void shouldIdentifyPaidPlans() {
        // Given
        User freeUser = User.createFreeUser("u1", "free@test.com", "Free", "hash");
        User silverUser = new User(
                "u2", "silver@test.com", "Silver", "hash",
                SubscriptionPlan.SILVER, SubscriptionStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1),
                new QuotaUsage(), "cus_123", "sub_123",
                LocalDateTime.now(), LocalDateTime.now());
        User goldUser = new User(
                "u3", "gold@test.com", "Gold", "hash",
                SubscriptionPlan.GOLD, SubscriptionStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1),
                new QuotaUsage(), "cus_456", "sub_456",
                LocalDateTime.now(), LocalDateTime.now());

        // Then
        assertThat(freeUser.isPaidPlan()).isFalse();
        assertThat(silverUser.isPaidPlan()).isTrue();
        assertThat(goldUser.isPaidPlan()).isTrue();
    }

    @Test
    @DisplayName("Should check if user can create vanity URLs")
    void shouldCheckCanCreateVanityUrls() {
        // Given - FREE user with quota available
        QuotaUsage quotaWithSpace = new QuotaUsage();
        quotaWithSpace.setVanityUrlsCreatedTotal(2); // FREE allows 3 total
        User freeUserWithQuota = new User(
                "u1", "test@example.com", "Test", "hash",
                SubscriptionPlan.FREE, SubscriptionStatus.ACTIVE,
                LocalDateTime.now(), null,
                quotaWithSpace, null, null,
                LocalDateTime.now(), LocalDateTime.now());

        // Given - FREE user quota exhausted
        QuotaUsage quotaExhausted = new QuotaUsage();
        quotaExhausted.setVanityUrlsCreatedTotal(3); // FREE allows 3 total
        User freeUserNoQuota = new User(
                "u2", "test2@example.com", "Test2", "hash",
                SubscriptionPlan.FREE, SubscriptionStatus.ACTIVE,
                LocalDateTime.now(), null,
                quotaExhausted, null, null,
                LocalDateTime.now(), LocalDateTime.now());

        // Given - SILVER user (monthly quota)
        QuotaUsage silverQuota = new QuotaUsage();
        silverQuota.setVanityUrlsCreatedThisMonth(24); // SILVER allows 25/month
        User silverUser = new User(
                "u3", "silver@example.com", "Silver", "hash",
                SubscriptionPlan.SILVER, SubscriptionStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1),
                silverQuota, "cus_123", "sub_123",
                LocalDateTime.now(), LocalDateTime.now());

        // Given - DIAMOND user (unlimited)
        User diamondUser = new User(
                "u4", "diamond@example.com", "Diamond", "hash",
                SubscriptionPlan.DIAMOND, SubscriptionStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusYears(1),
                new QuotaUsage(), "cus_999", "sub_999",
                LocalDateTime.now(), LocalDateTime.now());

        // Then
        assertThat(freeUserWithQuota.canCreateVanityUrls()).isTrue();
        assertThat(freeUserNoQuota.canCreateVanityUrls()).isFalse();
        assertThat(silverUser.canCreateVanityUrls()).isTrue();
        assertThat(diamondUser.canCreateVanityUrls()).isTrue();
    }

    @Test
    @DisplayName("Should handle subscription dates correctly")
    void shouldHandleSubscriptionDates() {
        // Given
        LocalDateTime startDate = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(2025, 2, 1, 0, 0);

        User user = new User(
                "user1", "test@example.com", "Test", "hash",
                SubscriptionPlan.GOLD, SubscriptionStatus.ACTIVE,
                startDate, endDate,
                new QuotaUsage(), "cus_123", "sub_123",
                LocalDateTime.now(), LocalDateTime.now());

        // Then
        assertThat(user.subscriptionStartDate()).isEqualTo(startDate);
        assertThat(user.subscriptionEndDate()).isEqualTo(endDate);
    }

    @Test
    @DisplayName("Should handle Stripe integration fields")
    void shouldHandleStripeFields() {
        // Given
        User paidUser = new User(
                "user1", "paid@example.com", "Paid User", "hash",
                SubscriptionPlan.GOLD, SubscriptionStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1),
                new QuotaUsage(), "cus_stripe123", "sub_stripe456",
                LocalDateTime.now(), LocalDateTime.now());

        User freeUser = User.createFreeUser("user2", "free@example.com", "Free User", "hash");

        // Then
        assertThat(paidUser.stripeCustomerId()).isEqualTo("cus_stripe123");
        assertThat(paidUser.stripeSubscriptionId()).isEqualTo("sub_stripe456");
        assertThat(freeUser.stripeCustomerId()).isNull();
        assertThat(freeUser.stripeSubscriptionId()).isNull();
    }
}
