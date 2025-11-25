package com.example.urlshortener.core.service;

import com.example.urlshortener.core.exception.QuotaExceededException;
import com.example.urlshortener.core.model.QuotaUsage;
import com.example.urlshortener.core.model.SubscriptionPlan;
import com.example.urlshortener.core.model.User;
import com.example.urlshortener.core.ports.outgoing.UserRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class QuotaService {

    private final UserRepositoryPort userRepository;

    public void checkVanityUrlQuota(User user, String alias) {
        SubscriptionPlan plan = user.plan();
        QuotaUsage usage = user.quotaUsage();

        // 1. Check monthly limit
        if (!plan.isUnlimited()) {
            int limit = plan.getVanityUrlsPerMonth();
            int currentUsage = plan == SubscriptionPlan.FREE ? usage.getVanityUrlsCreatedTotal()
                    : usage.getVanityUrlsCreatedThisMonth();

            if (currentUsage >= limit) {
                throw new QuotaExceededException(
                        "You've reached your limit of " + limit +
                                " vanity URLs. Upgrade your plan for more!");
            }
        }

        // 2. Check minimum alias length
        if (alias.length() < plan.getMinAliasLength()) {
            throw new QuotaExceededException(
                    "Aliases shorter than " + plan.getMinAliasLength() +
                            " characters require a higher tier plan.");
        }

        // 3. Check if quota reset is needed (lazy reset)
        if (usage.needsReset()) {
            resetMonthlyQuota(user);
        }
    }

    public void incrementVanityUrlUsage(User user) {
        QuotaUsage usage = user.quotaUsage();
        usage.setVanityUrlsCreatedThisMonth(usage.getVanityUrlsCreatedThisMonth() + 1);
        usage.setVanityUrlsCreatedTotal(usage.getVanityUrlsCreatedTotal() + 1);
        userRepository.save(user);
    }

    private void resetMonthlyQuota(User user) {
        QuotaUsage usage = user.quotaUsage();
        usage.resetMonthlyQuota();
        userRepository.save(user);
    }
}
