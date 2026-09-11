package ca.tyny.urlshortener.infra.adapter.output.redis;

import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRegistryPort;
import ca.tyny.urlshortener.core.ports.outgoing.CustomDomainRepositoryPort;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps the set of ACTIVE custom domains available on the redirect path.
 *
 * <p>The mirror is a Redis set ({@code url_shortener:custom_domains:active}) watched by a
 * short-lived in-memory snapshot (default refresh interval ~5s) so {@code isActiveHost} is a cheap,
 * non-blocking local check. Redis is the source for the snapshot; when unavailable the snapshot
 * falls back to the persistence layer to avoid a hard failure on the hot path (best-effort,
 * self-healing via {@link #refresh()}).
 */
@Component
public class RedisCustomDomainRegistry implements CustomDomainRegistryPort {

  private static final Logger log = LoggerFactory.getLogger(RedisCustomDomainRegistry.class);

  // CWE-117 defense at the sink: hosts are client-supplied when claiming domains
  static String logSafe(String value) {
    return value == null ? null : value.replace('\n', '_').replace('\r', '_');
  }

  static final String ACTIVE_DOMAINS_KEY = "url_shortener:custom_domains:active";
  static final long REFRESH_INTERVAL_MS = 5_000L;

  private final StringRedisTemplate redis;
  private final CustomDomainRepositoryPort repository;

  private final Object lock = new Object();
  private volatile Set<String> snapshot = Set.of();
  private volatile long lastRefresh = 0L;

  public RedisCustomDomainRegistry(
      StringRedisTemplate redis, CustomDomainRepositoryPort repository) {
    this.redis = redis;
    this.repository = repository;
  }

  @Override
  public boolean isActiveHost(String host) {
    ensureFresh();
    return snapshot.contains(host);
  }

  @Override
  public void markActive(String host) {
    try {
      redis.opsForSet().add(ACTIVE_DOMAINS_KEY, host);
    } catch (Exception e) {
      log.warn("Failed to record active domain {} in Redis: {}", logSafe(host), e.getMessage());
    }
    synchronized (lock) {
      Set<String> next = new java.util.HashSet<>(snapshot);
      next.add(host);
      snapshot = Set.copyOf(next);
    }
  }

  @Override
  public void markInactive(String host) {
    try {
      redis.opsForSet().remove(ACTIVE_DOMAINS_KEY, host);
    } catch (Exception e) {
      log.warn("Failed to remove active domain {} from Redis: {}", logSafe(host), e.getMessage());
    }
    synchronized (lock) {
      Set<String> next = new java.util.HashSet<>(snapshot);
      next.remove(host);
      snapshot = Set.copyOf(next);
    }
  }

  @Override
  public void refresh() {
    Set<String> next;
    try {
      Set<String> members = redis.opsForSet().members(ACTIVE_DOMAINS_KEY);
      next = members == null ? Set.of() : Set.copyOf(members);
    } catch (Exception e) {
      log.warn(
          "Redis snapshot failed for active custom domains; falling back to Mongo: {}",
          e.getMessage());
      next = loadFromPersistence();
    }
    synchronized (lock) {
      snapshot = next;
      lastRefresh = System.currentTimeMillis();
    }
  }

  private Set<String> loadFromPersistence() {
    try {
      return repository.findAll().stream()
          .filter(domain -> domain.status() == DomainStatus.ACTIVE)
          .map(CustomDomain::host)
          .collect(Collectors.toSet());
    } catch (Exception e) {
      log.error("Failed to load active custom domains from persistence: {}", e.getMessage());
      return Set.of();
    }
  }

  private void ensureFresh() {
    long now = System.currentTimeMillis();
    if (now - lastRefresh > REFRESH_INTERVAL_MS) {
      refresh();
    }
  }
}
