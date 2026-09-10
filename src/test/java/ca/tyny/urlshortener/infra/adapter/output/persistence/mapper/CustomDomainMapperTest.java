package ca.tyny.urlshortener.infra.adapter.output.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.infra.adapter.output.persistence.entity.CustomDomainEntity;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomDomainMapperTest {

  private final CustomDomainMapper mapper = new CustomDomainMapper();

  @Test
  @DisplayName("Maps domain record to entity and back without loss")
  void roundTripsDomainAndEntity() {
    Instant createdAt = Instant.parse("2026-08-28T00:00:00Z");
    CustomDomain domain =
        new CustomDomain(
            "links.marca.co", "user-1", DomainStatus.ACTIVE, "tok-abcd1234", createdAt);

    CustomDomainEntity entity = mapper.toPersistence(domain);
    assertThat(entity.getHost()).isEqualTo("links.marca.co");
    assertThat(entity.getUserId()).isEqualTo("user-1");
    assertThat(entity.getStatus()).isEqualTo(DomainStatus.ACTIVE);
    assertThat(entity.getVerificationToken()).isEqualTo("tok-abcd1234");
    assertThat(entity.getCreatedAt()).isEqualTo(createdAt);

    CustomDomain back = mapper.toDomain(entity);
    assertThat(back).isEqualTo(domain);
  }

  @Test
  @DisplayName("Rejects null inputs")
  void rejectsNullInputs() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.toPersistence(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.toDomain(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }
}
