package ca.tyny.urlshortener.infra.config.properties;

import ca.tyny.urlshortener.core.ports.outgoing.AdminEmailPort;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for the product ADMIN email list (prefix {@code app.admin-emails}).
 *
 * <p>ADMIN is bootstrapped from the environment, never from the database (ADR 0011 D1): an empty
 * list means there is no product admin at all. The env var {@code APP_ADMIN_EMAILS} is a
 * comma-separated list of emails; entries are normalized to trim + lowercase. Matches are
 * case-insensitive via {@link #isAdminEmail(String)}.
 */
@ConfigurationProperties(prefix = "app")
public record AdminProperties(@DefaultValue({}) List<String> adminEmails)
    implements AdminEmailPort {

  public AdminProperties {
    adminEmails =
        adminEmails == null
            ? List.of()
            : adminEmails.stream()
                .map(e -> e == null ? "" : e.trim().toLowerCase(Locale.ROOT))
                .filter(e -> !e.isEmpty())
                .sorted()
                .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public boolean isAdminEmail(String email) {
    if (email == null || email.isBlank()) {
      return false;
    }
    return adminEmails.contains(email.trim().toLowerCase(Locale.ROOT));
  }
}
