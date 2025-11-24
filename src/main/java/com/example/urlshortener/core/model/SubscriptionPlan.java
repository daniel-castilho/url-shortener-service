package com.example.urlshortener.core.model;

/**
 * Subscription plans with their respective quotas and features.
 * 
 * Each plan defines:
 * - vanityUrlsPerMonth: Number of custom aliases allowed per month (-1 =
 * unlimited)
 * - minAliasLength: Minimum length for custom aliases
 * - apiCallsPerMonth: API calls quota (-1 = unlimited)
 * - maxCustomDomains: Number of custom domains allowed (-1 = unlimited)
 * - whiteLabel: Whether white label branding is available
 */
public enum SubscriptionPlan {
    FREE(3, 8, 0, 0, false), // 3 vanity URLs total, min 8 chars
    SILVER(25, 5, 0, 0, false), // 25/month, min 5 chars
    GOLD(100, 4, 10_000, 1, false), // 100/month, min 4 chars, 10k API calls, 1 domain
    DIAMOND(-1, 3, -1, -1, true); // Unlimited, min 3 chars, unlimited API, unlimited domains

    private final int vanityUrlsPerMonth;
    private final int minAliasLength;
    private final int apiCallsPerMonth;
    private final int maxCustomDomains;
    private final boolean whiteLabel;

    SubscriptionPlan(int vanityUrlsPerMonth, int minAliasLength, int apiCallsPerMonth,
            int maxCustomDomains, boolean whiteLabel) {
        this.vanityUrlsPerMonth = vanityUrlsPerMonth;
        this.minAliasLength = minAliasLength;
        this.apiCallsPerMonth = apiCallsPerMonth;
        this.maxCustomDomains = maxCustomDomains;
        this.whiteLabel = whiteLabel;
    }

    public int getVanityUrlsPerMonth() {
        return vanityUrlsPerMonth;
    }

    public int getMinAliasLength() {
        return minAliasLength;
    }

    public int getApiCallsPerMonth() {
        return apiCallsPerMonth;
    }

    public int getMaxCustomDomains() {
        return maxCustomDomains;
    }

    public boolean isWhiteLabel() {
        return whiteLabel;
    }

    public boolean isUnlimited() {
        return vanityUrlsPerMonth == -1;
    }

    public boolean allowsApiAccess() {
        return apiCallsPerMonth != 0;
    }

    public boolean allowsCustomDomains() {
        return maxCustomDomains != 0;
    }
}
