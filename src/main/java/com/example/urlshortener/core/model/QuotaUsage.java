package com.example.urlshortener.core.model;

import java.time.LocalDateTime;

/**
 * Tracks quota usage for a user.
 * Quotas are reset monthly on the first day of each month.
 */
public class QuotaUsage {

    private int vanityUrlsCreatedThisMonth;
    private int vanityUrlsCreatedTotal;
    private int apiCallsThisMonth;
    private int customDomainsCount;
    private LocalDateTime quotaResetDate;

    public QuotaUsage() {
        this.vanityUrlsCreatedThisMonth = 0;
        this.vanityUrlsCreatedTotal = 0;
        this.apiCallsThisMonth = 0;
        this.customDomainsCount = 0;
        this.quotaResetDate = calculateNextResetDate(LocalDateTime.now());
    }

    public static LocalDateTime calculateNextResetDate(LocalDateTime fromDate) {
        return fromDate
                .plusMonths(1)
                .withDayOfMonth(1)
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
    }

    public void resetMonthlyQuota() {
        this.vanityUrlsCreatedThisMonth = 0;
        this.apiCallsThisMonth = 0;
        this.quotaResetDate = calculateNextResetDate(LocalDateTime.now());
    }

    public boolean needsReset() {
        return LocalDateTime.now().isAfter(quotaResetDate);
    }

    // Getters and Setters

    public int getVanityUrlsCreatedThisMonth() {
        return vanityUrlsCreatedThisMonth;
    }

    public void setVanityUrlsCreatedThisMonth(int vanityUrlsCreatedThisMonth) {
        this.vanityUrlsCreatedThisMonth = vanityUrlsCreatedThisMonth;
    }

    public int getVanityUrlsCreatedTotal() {
        return vanityUrlsCreatedTotal;
    }

    public void setVanityUrlsCreatedTotal(int vanityUrlsCreatedTotal) {
        this.vanityUrlsCreatedTotal = vanityUrlsCreatedTotal;
    }

    public int getApiCallsThisMonth() {
        return apiCallsThisMonth;
    }

    public void setApiCallsThisMonth(int apiCallsThisMonth) {
        this.apiCallsThisMonth = apiCallsThisMonth;
    }

    public int getCustomDomainsCount() {
        return customDomainsCount;
    }

    public void setCustomDomainsCount(int customDomainsCount) {
        this.customDomainsCount = customDomainsCount;
    }

    public LocalDateTime getQuotaResetDate() {
        return quotaResetDate;
    }

    public void setQuotaResetDate(LocalDateTime quotaResetDate) {
        this.quotaResetDate = quotaResetDate;
    }
}
