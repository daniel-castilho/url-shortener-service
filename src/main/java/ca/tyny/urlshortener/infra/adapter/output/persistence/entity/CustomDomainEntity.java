package ca.tyny.urlshortener.infra.adapter.output.persistence.entity;

import ca.tyny.urlshortener.core.model.DomainStatus;
import ca.tyny.urlshortener.infra.adapter.output.persistence.config.MongoCollections;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistence entity for a claimed custom domain.
 *
 * <p>The unique {@code host} index is created by migration V8 (auto-index-creation is disabled; the
 * schema is owned by migrations). The {@link #status} field stores the {@link DomainStatus} enum
 * name.
 */
@Document(collection = MongoCollections.CUSTOM_DOMAINS)
public class CustomDomainEntity {

  @Id private String id;

  /** Normalized host (e.g. {@code links.marca.co}); unique across claims. */
  @Indexed(unique = true)
  private String host;

  /** Owner id; a user may claim multiple hosts. */
  @Indexed private String userId;

  private DomainStatus status;

  /** DNS TXT verification token issued on claim / re-trigger. */
  private String verificationToken;

  private Instant createdAt;

  public CustomDomainEntity() {}

  public CustomDomainEntity(
      String host,
      String userId,
      DomainStatus status,
      String verificationToken,
      Instant createdAt) {
    this.host = host;
    this.userId = userId;
    this.status = status;
    this.verificationToken = verificationToken;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public DomainStatus getStatus() {
    return status;
  }

  public void setStatus(DomainStatus status) {
    this.status = status;
  }

  public String getVerificationToken() {
    return verificationToken;
  }

  public void setVerificationToken(String verificationToken) {
    this.verificationToken = verificationToken;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
