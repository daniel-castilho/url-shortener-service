package com.example.urlshortener.core.model;

import java.time.LocalDateTime;

/**
 * Domain model representing a user in the system.
 * 
 * This is the core domain entity that contains user information,
 * subscription details, and quota tracking.
 */
public record User(
        String id,
        String email,
        String name,
        String passwordHash,
        SubscriptionPlan plan,
        SubscriptionStatus status,
        LocalDateTime subscriptionStartDate,
        LocalDateTime subscriptionEndDate,
        QuotaUsage quotaUsage,
        String stripeCustomerId,
        String stripeSubscriptionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * Factory method to create a new FREE user
     */
    public static User createFreeUser(String id, String email, String name, String passwordHash) {
        QuotaUsage quota = new QuotaUsage();

        return new User(
                id,
                email,
                name,
                passwordHash,
                SubscriptionPlan.FREE,
                SubscriptionStatus.ACTIVE,
                LocalDateTime.now(),
                null, // No end date for FREE plan
                quota,
                null, // No Stripe customer yet
                null, // No Stripe subscription yet
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    /**
     * Check if user has an active subscription
     */
    public boolean hasActiveSubscription() {
        return status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIAL;
    }

    /**
     * Check if user is on a paid plan
     */
    public boolean isPaidPlan() {
        return plan != SubscriptionPlan.FREE;
    }

    /**
     * Check if user can create vanity URLs
     */
    public boolean canCreateVanityUrls() {
        return hasActiveSubscription();
    }
}
