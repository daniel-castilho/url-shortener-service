package ca.tyny.urlshortener.infra.adapter.output.persistence.mapper;

import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.CustomDomainEntity;
import org.springframework.stereotype.Component;

/** Maps a {@link CustomDomain} domain record to/from its persistence entity. */
@Component
public class CustomDomainMapper {

  public CustomDomainEntity toPersistence(CustomDomain domain) {
    if (domain == null) {
      throw new IllegalArgumentException("Domain object cannot be null");
    }
    return new CustomDomainEntity(
        domain.host(),
        domain.userId(),
        domain.status(),
        domain.verificationToken(),
        domain.createdAt());
  }

  public CustomDomain toDomain(CustomDomainEntity entity) {
    if (entity == null) {
      throw new IllegalArgumentException("Entity object cannot be null");
    }
    return new CustomDomain(
        entity.getHost(),
        entity.getUserId(),
        entity.getStatus(),
        entity.getVerificationToken(),
        entity.getCreatedAt());
  }
}
