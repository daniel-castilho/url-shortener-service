package com.example.urlshortener.core.service;

import com.example.urlshortener.core.exception.QuotaExceededException;
import com.example.urlshortener.core.model.QuotaUsage;
import com.example.urlshortener.core.model.SubscriptionPlan;
import com.example.urlshortener.core.model.User;
import com.example.urlshortener.core.ports.outgoing.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private UserRepositoryPort userRepository;

    @InjectMocks
    private QuotaService quotaService;

    private User freeUser;
    private User silverUser;

    @BeforeEach
    void setUp() {
        freeUser = User.createFreeUser("user1", "free@example.com", "Free User", "hash");

        // Setup Silver User manually as we don't have a factory method yet
        QuotaUsage silverQuota = new QuotaUsage();
        silverUser = new User(
                "user2",
                "silver@example.com",
                "Silver User",
                "hash",
                SubscriptionPlan.SILVER,
                com.example.urlshortener.core.model.SubscriptionStatus.ACTIVE,
                LocalDateTime.now(),
                LocalDateTime.now().plusMonths(1),
                silverQuota,
                "cust_123",
                "sub_123",
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    @Test
    @DisplayName("Should allow alias creation within quota")
    void shouldAllowAliasWithinQuota() {
        assertDoesNotThrow(() -> quotaService.checkVanityUrlQuota(freeUser, "valid-alias"));
    }

    @Test
    @DisplayName("Should throw exception when monthly limit exceeded")
    void shouldThrowWhenLimitExceeded() {
        // Given free plan limit is 3
        freeUser.quotaUsage().setVanityUrlsCreatedTotal(3);

        // When/Then
        assertThrows(QuotaExceededException.class, () -> quotaService.checkVanityUrlQuota(freeUser, "valid-alias"));
    }

    @Test
    @DisplayName("Should throw exception when alias is too short")
    void shouldThrowWhenAliasTooShort() {
        // Free plan min length is 8
        String shortAlias = "short";

        assertThrows(QuotaExceededException.class, () -> quotaService.checkVanityUrlQuota(freeUser, shortAlias));
    }

    @Test
    @DisplayName("Should allow shorter alias for premium plan")
    void shouldAllowShorterAliasForPremium() {
        // Silver plan min length is 5
        String alias = "abcde";

        assertDoesNotThrow(() -> quotaService.checkVanityUrlQuota(silverUser, alias));
    }

    @Test
    @DisplayName("Should increment usage")
    void shouldIncrementUsage() {
        int initialUsage = freeUser.quotaUsage().getVanityUrlsCreatedTotal();

        quotaService.incrementVanityUrlUsage(freeUser);

        assertEquals(initialUsage + 1, freeUser.quotaUsage().getVanityUrlsCreatedTotal());
        verify(userRepository).save(freeUser);
    }
}
