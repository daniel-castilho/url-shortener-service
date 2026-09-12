package ca.tyny.urlshortener.infra.config;

import ca.tyny.urlshortener.infra.config.properties.RedisClientProperties;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
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

  /**
   * Enforce the ADR 0005 Redis timeout budget on the Redisson client. The starter's auto-config
   * applies customizers right after building the single-server Config, so this bounds the command
   * and connect timeouts (and disables slow multi-retry) that would otherwise make every Redis op
   * block for seconds during an outage instead of failing open fast.
   */
  @Bean
  public RedissonAutoConfigurationCustomizer redissonTimeoutCustomizer(
      RedisClientProperties properties) {
    return config ->
        config
            .useSingleServer()
            .setTimeout(properties.commandTimeoutMs())
            .setConnectTimeout(properties.connectTimeoutMs())
            .setRetryAttempts(properties.retryAttempts())
            .setRetryInterval(properties.retryIntervalMs());
  }
}
