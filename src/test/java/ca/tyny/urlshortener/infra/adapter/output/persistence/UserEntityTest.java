package ca.tyny.urlshortener.infra.adapter.output.persistence;

import ca.tyny.urlshortener.core.model.QuotaUsage;
import ca.tyny.urlshortener.core.model.SubscriptionPlan;
import ca.tyny.urlshortener.core.model.SubscriptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class UserEntityTest {

    @Test
    @DisplayName("Should create entity with all-args constructor")
    void shouldCreateEntityWithAllArgs() {
        LocalDateTime now = LocalDateTime.now();
        QuotaUsage quota = new QuotaUsage();

        UserEntity entity = new UserEntity(
                "user1", "test@example.com", "Test User", "hash123",
                SubscriptionPlan.SILVER, SubscriptionStatus.ACTIVE,
                now, now.plusMonths(1), quota,
                "cust_123", "sub_456", now, now);

        assertThat(entity.getId()).isEqualTo("user1");
        assertThat(entity.getEmail()).isEqualTo("test@example.com");
        assertThat(entity.getName()).isEqualTo("Test User");
        assertThat(entity.getPasswordHash()).isEqualTo("hash123");
        assertThat(entity.getPlan()).isEqualTo(SubscriptionPlan.SILVER);
        assertThat(entity.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(entity.getSubscriptionStartDate()).isEqualTo(now);
        assertThat(entity.getSubscriptionEndDate()).isEqualTo(now.plusMonths(1));
        assertThat(entity.getQuotaUsage()).isEqualTo(quota);
        assertThat(entity.getStripeCustomerId()).isEqualTo("cust_123");
        assertThat(entity.getStripeSubscriptionId()).isEqualTo("sub_456");
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Should create entity with no-args constructor")
    void shouldCreateEntityWithNoArgs() {
        UserEntity entity = new UserEntity();

        assertThat(entity.getId()).isNull();
        assertThat(entity.getEmail()).isNull();
        assertThat(entity.getName()).isNull();
        assertThat(entity.getPasswordHash()).isNull();
        assertThat(entity.getPlan()).isNull();
        assertThat(entity.getStatus()).isNull();
        assertThat(entity.getSubscriptionStartDate()).isNull();
        assertThat(entity.getSubscriptionEndDate()).isNull();
        assertThat(entity.getQuotaUsage()).isNull();
        assertThat(entity.getStripeCustomerId()).isNull();
        assertThat(entity.getStripeSubscriptionId()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("Should set and get all fields")
    void shouldSetAndGetAllFields() {
        LocalDateTime now = LocalDateTime.now();
        QuotaUsage quota = new QuotaUsage();
        UserEntity entity = new UserEntity();

        entity.setId("user1");
        entity.setEmail("test@example.com");
        entity.setName("Test User");
        entity.setPasswordHash("hash123");
        entity.setPlan(SubscriptionPlan.GOLD);
        entity.setStatus(SubscriptionStatus.EXPIRED);
        entity.setSubscriptionStartDate(now);
        entity.setSubscriptionEndDate(now.plusMonths(1));
        entity.setQuotaUsage(quota);
        entity.setStripeCustomerId("cust_123");
        entity.setStripeSubscriptionId("sub_456");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertThat(entity.getId()).isEqualTo("user1");
        assertThat(entity.getEmail()).isEqualTo("test@example.com");
        assertThat(entity.getName()).isEqualTo("Test User");
        assertThat(entity.getPasswordHash()).isEqualTo("hash123");
        assertThat(entity.getPlan()).isEqualTo(SubscriptionPlan.GOLD);
        assertThat(entity.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(entity.getSubscriptionStartDate()).isEqualTo(now);
        assertThat(entity.getSubscriptionEndDate()).isEqualTo(now.plusMonths(1));
        assertThat(entity.getQuotaUsage()).isEqualTo(quota);
        assertThat(entity.getStripeCustomerId()).isEqualTo("cust_123");
        assertThat(entity.getStripeSubscriptionId()).isEqualTo("sub_456");
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now);
    }
}
