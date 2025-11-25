package com.example.urlshortener.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QuotaUsage Tests")
class QuotaUsageTest {

    @Test
    @DisplayName("Should initialize with zero values and next month reset date")
    void shouldInitializeWithDefaults() {
        // When
        QuotaUsage quota = new QuotaUsage();

        // Then
        assertThat(quota.getVanityUrlsCreatedThisMonth()).isZero();
        assertThat(quota.getVanityUrlsCreatedTotal()).isZero();
        assertThat(quota.getApiCallsThisMonth()).isZero();
        assertThat(quota.getCustomDomainsCount()).isZero();
        assertThat(quota.getQuotaResetDate()).isNotNull();
        assertThat(quota.getQuotaResetDate()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("Should reset monthly quota correctly")
    void shouldResetMonthlyQuota() {
        // Given
        QuotaUsage quota = new QuotaUsage();
        quota.setVanityUrlsCreatedThisMonth(50);
        quota.setVanityUrlsCreatedTotal(100);
        quota.setApiCallsThisMonth(5000);
        quota.setCustomDomainsCount(2);

        // Set reset date to past to ensure new date is different
        LocalDateTime oldResetDate = LocalDateTime.now().minusMonths(1);
        quota.setQuotaResetDate(oldResetDate);

        // When
        quota.resetMonthlyQuota();

        // Then
        assertThat(quota.getVanityUrlsCreatedThisMonth()).isZero();
        assertThat(quota.getVanityUrlsCreatedTotal()).isEqualTo(100); // Total should NOT reset
        assertThat(quota.getApiCallsThisMonth()).isZero();
        assertThat(quota.getCustomDomainsCount()).isEqualTo(2); // Domains should NOT reset
        assertThat(quota.getQuotaResetDate()).isAfter(oldResetDate);
    }

    @Test
    @DisplayName("Should detect when reset is needed")
    void shouldDetectResetNeeded() {
        // Given - Reset date in the past
        QuotaUsage quotaNeedsReset = new QuotaUsage();
        quotaNeedsReset.setQuotaResetDate(LocalDateTime.now().minusDays(1));

        // Given - Reset date in the future
        QuotaUsage quotaNoReset = new QuotaUsage();
        quotaNoReset.setQuotaResetDate(LocalDateTime.now().plusDays(15));

        // Then
        assertThat(quotaNeedsReset.needsReset()).isTrue();
        assertThat(quotaNoReset.needsReset()).isFalse();
    }

    @Test
    @DisplayName("Should calculate next reset date correctly")
    void shouldCalculateNextResetDate() {
        // Given
        LocalDateTime now = LocalDateTime.of(2025, 1, 15, 10, 30);

        // When
        LocalDateTime nextReset = QuotaUsage.calculateNextResetDate(now);

        // Then
        assertThat(nextReset.getYear()).isEqualTo(2025);
        assertThat(nextReset.getMonthValue()).isEqualTo(2); // Next month
        assertThat(nextReset.getDayOfMonth()).isEqualTo(1); // First day
        assertThat(nextReset.getHour()).isZero();
        assertThat(nextReset.getMinute()).isZero();
        assertThat(nextReset.getSecond()).isZero();
    }

    @Test
    @DisplayName("Should calculate next reset date for December correctly")
    void shouldCalculateNextResetDateForDecember() {
        // Given - December
        LocalDateTime december = LocalDateTime.of(2025, 12, 25, 15, 45);

        // When
        LocalDateTime nextReset = QuotaUsage.calculateNextResetDate(december);

        // Then
        assertThat(nextReset.getYear()).isEqualTo(2026); // Next year
        assertThat(nextReset.getMonthValue()).isEqualTo(1); // January
        assertThat(nextReset.getDayOfMonth()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should increment monthly vanity URLs correctly")
    void shouldIncrementMonthlyVanityUrls() {
        // Given
        QuotaUsage quota = new QuotaUsage();

        // When
        quota.setVanityUrlsCreatedThisMonth(quota.getVanityUrlsCreatedThisMonth() + 1);
        quota.setVanityUrlsCreatedTotal(quota.getVanityUrlsCreatedTotal() + 1);

        // Then
        assertThat(quota.getVanityUrlsCreatedThisMonth()).isEqualTo(1);
        assertThat(quota.getVanityUrlsCreatedTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should increment API calls correctly")
    void shouldIncrementApiCalls() {
        // Given
        QuotaUsage quota = new QuotaUsage();

        // When
        quota.setApiCallsThisMonth(quota.getApiCallsThisMonth() + 100);

        // Then
        assertThat(quota.getApiCallsThisMonth()).isEqualTo(100);
    }

    @Test
    @DisplayName("Should track custom domains count")
    void shouldTrackCustomDomains() {
        // Given
        QuotaUsage quota = new QuotaUsage();

        // When
        quota.setCustomDomainsCount(3);

        // Then
        assertThat(quota.getCustomDomainsCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should handle quota reset workflow")
    void shouldHandleQuotaResetWorkflow() {
        // Given - User with expired quota
        QuotaUsage quota = new QuotaUsage();
        quota.setVanityUrlsCreatedThisMonth(25);
        quota.setVanityUrlsCreatedTotal(50);
        quota.setApiCallsThisMonth(10000);
        quota.setQuotaResetDate(LocalDateTime.now().minusDays(5)); // Expired

        // When - Check and reset if needed
        if (quota.needsReset()) {
            quota.resetMonthlyQuota();
        }

        // Then
        assertThat(quota.getVanityUrlsCreatedThisMonth()).isZero();
        assertThat(quota.getVanityUrlsCreatedTotal()).isEqualTo(50);
        assertThat(quota.getApiCallsThisMonth()).isZero();
        assertThat(quota.needsReset()).isFalse();
    }
}
