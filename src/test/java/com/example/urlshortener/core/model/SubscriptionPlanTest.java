package com.example.urlshortener.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SubscriptionPlan Tests")
class SubscriptionPlanTest {

    @Test
    @DisplayName("FREE plan should have correct quotas")
    void freePlanShouldHaveCorrectQuotas() {
        // Given
        SubscriptionPlan plan = SubscriptionPlan.FREE;

        // Then
        assertThat(plan.getVanityUrlsPerMonth()).isEqualTo(3);
        assertThat(plan.getMinAliasLength()).isEqualTo(8);
        assertThat(plan.getApiCallsPerMonth()).isZero();
        assertThat(plan.getMaxCustomDomains()).isZero();
        assertThat(plan.isWhiteLabel()).isFalse();
    }

    @Test
    @DisplayName("SILVER plan should have correct quotas")
    void silverPlanShouldHaveCorrectQuotas() {
        // Given
        SubscriptionPlan plan = SubscriptionPlan.SILVER;

        // Then
        assertThat(plan.getVanityUrlsPerMonth()).isEqualTo(25);
        assertThat(plan.getMinAliasLength()).isEqualTo(5);
        assertThat(plan.getApiCallsPerMonth()).isZero();
        assertThat(plan.getMaxCustomDomains()).isZero();
        assertThat(plan.isWhiteLabel()).isFalse();
    }

    @Test
    @DisplayName("GOLD plan should have correct quotas")
    void goldPlanShouldHaveCorrectQuotas() {
        // Given
        SubscriptionPlan plan = SubscriptionPlan.GOLD;

        // Then
        assertThat(plan.getVanityUrlsPerMonth()).isEqualTo(100);
        assertThat(plan.getMinAliasLength()).isEqualTo(4);
        assertThat(plan.getApiCallsPerMonth()).isEqualTo(10_000);
        assertThat(plan.getMaxCustomDomains()).isEqualTo(1);
        assertThat(plan.isWhiteLabel()).isFalse();
    }

    @Test
    @DisplayName("DIAMOND plan should have unlimited quotas")
    void diamondPlanShouldHaveUnlimitedQuotas() {
        // Given
        SubscriptionPlan plan = SubscriptionPlan.DIAMOND;

        // Then
        assertThat(plan.getVanityUrlsPerMonth()).isEqualTo(-1); // Unlimited
        assertThat(plan.getMinAliasLength()).isEqualTo(3);
        assertThat(plan.getApiCallsPerMonth()).isEqualTo(-1); // Unlimited
        assertThat(plan.getMaxCustomDomains()).isEqualTo(-1); // Unlimited
        assertThat(plan.isWhiteLabel()).isTrue();
    }

    @Test
    @DisplayName("Should identify unlimited plans correctly")
    void shouldIdentifyUnlimitedPlans() {
        // Then
        assertThat(SubscriptionPlan.FREE.isUnlimited()).isFalse();
        assertThat(SubscriptionPlan.SILVER.isUnlimited()).isFalse();
        assertThat(SubscriptionPlan.GOLD.isUnlimited()).isFalse();
        assertThat(SubscriptionPlan.DIAMOND.isUnlimited()).isTrue();
    }

    @Test
    @DisplayName("Should identify plans with API access")
    void shouldIdentifyPlansWithApiAccess() {
        // Then
        assertThat(SubscriptionPlan.FREE.allowsApiAccess()).isFalse();
        assertThat(SubscriptionPlan.SILVER.allowsApiAccess()).isFalse();
        assertThat(SubscriptionPlan.GOLD.allowsApiAccess()).isTrue();
        assertThat(SubscriptionPlan.DIAMOND.allowsApiAccess()).isTrue();
    }

    @Test
    @DisplayName("Should identify plans with custom domains")
    void shouldIdentifyPlansWithCustomDomains() {
        // Then
        assertThat(SubscriptionPlan.FREE.allowsCustomDomains()).isFalse();
        assertThat(SubscriptionPlan.SILVER.allowsCustomDomains()).isFalse();
        assertThat(SubscriptionPlan.GOLD.allowsCustomDomains()).isTrue();
        assertThat(SubscriptionPlan.DIAMOND.allowsCustomDomains()).isTrue();
    }

    @Test
    @DisplayName("Should have progressive alias length requirements")
    void shouldHaveProgressiveAliasLength() {
        // Then - Higher tiers allow shorter aliases
        assertThat(SubscriptionPlan.FREE.getMinAliasLength())
                .isGreaterThan(SubscriptionPlan.SILVER.getMinAliasLength());
        assertThat(SubscriptionPlan.SILVER.getMinAliasLength())
                .isGreaterThan(SubscriptionPlan.GOLD.getMinAliasLength());
        assertThat(SubscriptionPlan.GOLD.getMinAliasLength())
                .isGreaterThan(SubscriptionPlan.DIAMOND.getMinAliasLength());
    }

    @Test
    @DisplayName("Should have progressive vanity URL quotas")
    void shouldHaveProgressiveVanityUrlQuotas() {
        // Then - Higher tiers have more vanity URLs (excluding unlimited)
        assertThat(SubscriptionPlan.FREE.getVanityUrlsPerMonth())
                .isLessThan(SubscriptionPlan.SILVER.getVanityUrlsPerMonth());
        assertThat(SubscriptionPlan.SILVER.getVanityUrlsPerMonth())
                .isLessThan(SubscriptionPlan.GOLD.getVanityUrlsPerMonth());
    }

    @Test
    @DisplayName("Should validate alias length against plan")
    void shouldValidateAliasLengthAgainstPlan() {
        // Given
        String shortAlias = "abc"; // 3 chars
        String mediumAlias = "abcd"; // 4 chars
        String longAlias = "abcdefgh"; // 8 chars

        // Then - FREE requires 8+ chars
        assertThat(shortAlias.length()).isLessThan(SubscriptionPlan.FREE.getMinAliasLength());
        assertThat(longAlias.length()).isGreaterThanOrEqualTo(SubscriptionPlan.FREE.getMinAliasLength());

        // Then - DIAMOND allows 3+ chars
        assertThat(shortAlias.length()).isGreaterThanOrEqualTo(SubscriptionPlan.DIAMOND.getMinAliasLength());
    }

    @Test
    @DisplayName("Only DIAMOND should have white label")
    void onlyDiamondShouldHaveWhiteLabel() {
        // Then
        assertThat(SubscriptionPlan.FREE.isWhiteLabel()).isFalse();
        assertThat(SubscriptionPlan.SILVER.isWhiteLabel()).isFalse();
        assertThat(SubscriptionPlan.GOLD.isWhiteLabel()).isFalse();
        assertThat(SubscriptionPlan.DIAMOND.isWhiteLabel()).isTrue();
    }
}
