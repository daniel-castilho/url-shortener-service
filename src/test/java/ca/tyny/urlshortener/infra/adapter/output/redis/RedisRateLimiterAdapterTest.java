package ca.tyny.urlshortener.infra.adapter.output.redis;

import ca.tyny.urlshortener.core.model.RateLimitVerdict;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimitScope;
import ca.tyny.urlshortener.infra.config.properties.RateLimiterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisRateLimiterAdapterTest {

    private StringRedisTemplate template;
    private RedisRateLimiterAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(StringRedisTemplate.class);
        adapter = new RedisRateLimiterAdapter(template, new RateLimiterProperties(
                true, 60, Duration.ofMinutes(1), 120, Duration.ofMinutes(1),
                "X-Forwarded-For", List.of("127.0.0.0/8")));
    }

    private void reply(List<Long> value) {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn((List) value);
    }

    @Test
    @DisplayName("Allows while tokens remain")
    void allowsWithinLimit() {
        reply(List.of(1L, 59L, 0L));

        RateLimitVerdict verdict = adapter.tryAcquire(RateLimitScope.REDIRECT, "1.2.3.4");

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.remainingTokens()).isEqualTo(59);
    }

    @Test
    @DisplayName("Blocks with reset seconds when the bucket is empty")
    void blocksWhenEmpty() {
        reply(List.of(0L, 0L, 7L));

        RateLimitVerdict verdict = adapter.tryAcquire(RateLimitScope.SHORTEN, "1.2.3.4");

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.resetSeconds()).isEqualTo(7);
    }

    @Test
    @DisplayName("Fails open on null or malformed replies")
    void failsOpenOnBadReply() {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(null).thenReturn(List.of(1L));

        assertThat(adapter.tryAcquire(RateLimitScope.REDIRECT, "1.2.3.4").allowed()).isTrue();
        assertThat(adapter.tryAcquire(RateLimitScope.REDIRECT, "1.2.3.4").allowed()).isTrue();
    }

    @Test
    @DisplayName("Fail-open fallback method returns allow with max remaining")
    void fallbackMethodAllows() {
        assertThat(adapter.allowRequestOnFailure(
                ca.tyny.urlshortener.core.ports.outgoing.RateLimitScope.REDIRECT,
                "5.6.7.8",
                new RuntimeException("connection refused")).allowed())
                .isTrue();
        org.mockito.Mockito.verifyNoInteractions(template);
    }

    @Test
    @DisplayName("Disabled limiter never touches Redis")
    void disabledBypassesRedis() {
        adapter = new RedisRateLimiterAdapter(template, new RateLimiterProperties(
                false, 60, Duration.ofMinutes(1), 120, Duration.ofMinutes(1),
                "X-Forwarded-For", List.of()));

        RateLimitVerdict verdict = adapter.tryAcquire(RateLimitScope.REDIRECT, "9.9.9.9");

        assertThat(verdict.allowed()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(template);
    }

    @Test
    @DisplayName("Bucket keys are structurally distinct per scope")
    void keysAreScoped() {
        assertThat(RedisRateLimiterAdapter.bucketKey(RateLimitScope.REDIRECT, "1.2.3.4"))
                .isEqualTo("rl:redirect:1.2.3.4");
        assertThat(RedisRateLimiterAdapter.bucketKey(RateLimitScope.SHORTEN, "1.2.3.4"))
                .isEqualTo("rl:shorten:1.2.3.4");
    }
}
