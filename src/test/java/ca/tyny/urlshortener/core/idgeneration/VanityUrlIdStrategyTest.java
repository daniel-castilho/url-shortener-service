package ca.tyny.urlshortener.core.idgeneration;

import ca.tyny.urlshortener.core.model.SubscriptionPlan;
import ca.tyny.urlshortener.core.model.SubscriptionStatus;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VanityUrlIdStrategyTest {

    @Mock
    private UserRepositoryPort userRepository;

    @Mock
    private UrlRepositoryPort urlRepository;

    private VanityUrlIdStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new VanityUrlIdStrategy(userRepository, urlRepository);
    }

    @Test
    @DisplayName("Should support when custom alias is provided")
    void shouldSupportWithCustomAlias() {
        assertTrue(strategy.supports("my-custom-alias"));
    }

    @Test
    @DisplayName("Should not support when custom alias is null")
    void shouldNotSupportWithNullAlias() {
        assertFalse(strategy.supports(null));
    }

    @Test
    @DisplayName("Should not support when custom alias is blank")
    void shouldNotSupportWithBlankAlias() {
        assertFalse(strategy.supports(""));
        assertFalse(strategy.supports("   "));
    }

    @Test
    @DisplayName("Should throw when userId is null")
    void shouldThrowWhenUserIdIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> strategy.generateId("my-alias", null));
    }

    @Test
    @DisplayName("Should throw when user not found")
    void shouldThrowWhenUserNotFound() {
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> strategy.generateId("my-alias", "user123"));
    }

    @Test
    @DisplayName("Should throw when user cannot create vanity URLs")
    void shouldThrowWhenUserCannotCreateVanityUrls() {
        User freeUser = User.createFreeUser("user1", "test@example.com", "Test User", "hash");
        when(userRepository.findById("user1")).thenReturn(Optional.of(freeUser));

        // Free plan has limit of 3 vanity URLs, set to limit
        freeUser.quotaUsage().setVanityUrlsCreatedTotal(3);

        assertThrows(IllegalArgumentException.class,
                () -> strategy.generateId("my-alias", "user1"));
    }

    @Test
    @DisplayName("Should throw when alias format is invalid")
    void shouldThrowWhenAliasFormatIsInvalid() {
        User user = User.createFreeUser("user1", "test@example.com", "Test User", "hash");
        when(userRepository.findById("user1")).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class,
                () -> strategy.generateId("invalid alias!", "user1"));
    }

    @Test
    @DisplayName("Should throw when alias already exists")
    void shouldThrowWhenAliasAlreadyExists() {
        User user = User.createFreeUser("user1", "test@example.com", "Test User", "hash");
        when(userRepository.findById("user1")).thenReturn(Optional.of(user));
        when(urlRepository.existsById("existing-alias")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> strategy.generateId("existing-alias", "user1"));
    }

    @Test
    @DisplayName("Should return alias when all validations pass")
    void shouldReturnAliasWhenAllValidationsPass() {
        User user = User.createFreeUser("user1", "test@example.com", "Test User", "hash");
        when(userRepository.findById("user1")).thenReturn(Optional.of(user));
        when(urlRepository.existsById("valid-alias")).thenReturn(false);

        String result = strategy.generateId("valid-alias", "user1");

        assertEquals("valid-alias", result);
        verify(userRepository).findById("user1");
        verify(urlRepository).existsById("valid-alias");
    }

    @Test
    @DisplayName("Should allow alias with hyphen and underscore")
    void shouldAllowAliasWithHyphenAndUnderscore() {
        User user = User.createFreeUser("user1", "test@example.com", "Test User", "hash");
        when(userRepository.findById("user1")).thenReturn(Optional.of(user));
        when(urlRepository.existsById("my-link_123")).thenReturn(false);

        String result = strategy.generateId("my-link_123", "user1");

        assertEquals("my-link_123", result);
    }

    @Test
    @DisplayName("Should allow shorter alias for premium plan")
    void shouldAllowShorterAliasForPremiumPlan() {
        User silverUser = new User(
                "user2",
                "silver@example.com",
                "Silver User",
                "hash",
                SubscriptionPlan.SILVER,
                SubscriptionStatus.ACTIVE,
                LocalDateTime.now(),
                LocalDateTime.now().plusMonths(1),
                new ca.tyny.urlshortener.core.model.QuotaUsage(),
                "cust_123",
                "sub_123",
                LocalDateTime.now(),
                LocalDateTime.now());
        when(userRepository.findById("user2")).thenReturn(Optional.of(silverUser));
        when(urlRepository.existsById("abcde")).thenReturn(false);

        String result = strategy.generateId("abcde", "user2");

        assertEquals("abcde", result);
    }
}
