package com.example.urlshortener.infra.config;

import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public com.example.urlshortener.core.ports.outgoing.RateLimiterPort rateLimiterPort(RedissonClient redisson,
            @Value("${rate-limiter.limit:60}") long limit,
            @Value("${rate-limiter.window:PT1M}") java.time.Duration window) {
        return new com.example.urlshortener.infra.adapter.output.redis.RedisRateLimiterAdapter(redisson, limit, window);
    }
}
