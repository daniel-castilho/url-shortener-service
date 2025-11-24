package com.example.urlshortener.infra.adapter.output.persistence;

import com.example.urlshortener.core.model.QuotaUsage;
import com.example.urlshortener.core.model.SubscriptionPlan;
import com.example.urlshortener.core.model.SubscriptionStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * MongoDB entity for User persistence.
 */
@Document(collection = "users")
public class UserEntity {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    private String name;
    private String passwordHash;

    @Indexed
    private SubscriptionPlan plan;

    private SubscriptionStatus status;

    private LocalDateTime subscriptionStartDate;
    private LocalDateTime subscriptionEndDate;

    private QuotaUsage quotaUsage;

    // Stripe integration
    private String stripeCustomerId;
    private String stripeSubscriptionId;

    @Indexed
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Constructors

    public UserEntity() {
    }

    public UserEntity(String id, String email, String name, String passwordHash,
            SubscriptionPlan plan, SubscriptionStatus status,
            LocalDateTime subscriptionStartDate, LocalDateTime subscriptionEndDate,
            QuotaUsage quotaUsage, String stripeCustomerId, String stripeSubscriptionId,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.plan = plan;
        this.status = status;
        this.subscriptionStartDate = subscriptionStartDate;
        this.subscriptionEndDate = subscriptionEndDate;
        this.quotaUsage = quotaUsage;
        this.stripeCustomerId = stripeCustomerId;
        this.stripeSubscriptionId = stripeSubscriptionId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public SubscriptionPlan getPlan() {
        return plan;
    }

    public void setPlan(SubscriptionPlan plan) {
        this.plan = plan;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public void setStatus(SubscriptionStatus status) {
        this.status = status;
    }

    public LocalDateTime getSubscriptionStartDate() {
        return subscriptionStartDate;
    }

    public void setSubscriptionStartDate(LocalDateTime subscriptionStartDate) {
        this.subscriptionStartDate = subscriptionStartDate;
    }

    public LocalDateTime getSubscriptionEndDate() {
        return subscriptionEndDate;
    }

    public void setSubscriptionEndDate(LocalDateTime subscriptionEndDate) {
        this.subscriptionEndDate = subscriptionEndDate;
    }

    public QuotaUsage getQuotaUsage() {
        return quotaUsage;
    }

    public void setQuotaUsage(QuotaUsage quotaUsage) {
        this.quotaUsage = quotaUsage;
    }

    public String getStripeCustomerId() {
        return stripeCustomerId;
    }

    public void setStripeCustomerId(String stripeCustomerId) {
        this.stripeCustomerId = stripeCustomerId;
    }

    public String getStripeSubscriptionId() {
        return stripeSubscriptionId;
    }

    public void setStripeSubscriptionId(String stripeSubscriptionId) {
        this.stripeSubscriptionId = stripeSubscriptionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
