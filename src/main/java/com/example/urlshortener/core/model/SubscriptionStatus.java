package com.example.urlshortener.core.model;

/**
 * Status of a user's subscription.
 */
public enum SubscriptionStatus {
    /**
     * Subscription is active and in good standing
     */
    ACTIVE,

    /**
     * User is in trial period
     */
    TRIAL,

    /**
     * Subscription has been canceled but still active until end of billing period
     */
    CANCELED,

    /**
     * Subscription has expired
     */
    EXPIRED,

    /**
     * Payment is past due
     */
    PAST_DUE
}
