package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.QuotaExceededException;
import ca.tyny.urlshortener.core.model.QuotaUsage;
import ca.tyny.urlshortener.core.model.SubscriptionPlan;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;

public class QuotaService {

    private final UserRepositoryPort userRepository;

    public QuotaService(UserRepositoryPort userRepository) {
        this.userRepository = userRepository;
    }

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
        // Atomic server-side increment ($inc) — read-modify-write loses updates
        // under concurrency (AGENTS.md debt item 14).
        userRepository.incrementVanityUsage(user.id());
    }

    private void resetMonthlyQuota(User user) {
        QuotaUsage usage = user.quotaUsage();
        usage.resetMonthlyQuota();
        userRepository.save(user);
    }
}
