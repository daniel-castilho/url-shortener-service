package ca.tyny.urlshortener.infra.adapter.output.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.infra.adapter.output.persistence.MongoClickEventRepository;
import com.mongodb.client.result.DeleteResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClickEventsRetentionPurge Tests")
class ClickEventsRetentionPurgeTest {

  @Mock private MongoTemplate mongoTemplate;

  @Mock private MongoClickEventRepository clickEventRepository;

  private MeterRegistry meterRegistry;
  private ClickEventsRetentionPurge purge;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    purge = new ClickEventsRetentionPurge(mongoTemplate, clickEventRepository, meterRegistry, 90);
  }

  private DeleteResult result(long deleted) {
    return DeleteResult.acknowledged(deleted);
  }

  @Test
  @DisplayName("Purges old events in batches until a batch comes back empty")
  @TracesRequirement("REQ-ANALYTICS-005")
  void purgesInBatchesUntilEmpty() {
    // Two full batches, then an empty one -> loop stops
    when(mongoTemplate.remove(any(Query.class), anyString()))
        .thenReturn(result(1000))
        .thenReturn(result(1000))
        .thenReturn(result(0));

    purge.purge();

    verify(mongoTemplate, times(3)).remove(any(Query.class), anyString());
    assertThat(meterRegistry.get("analytics.retention.purged.total").counter().count())
        .isEqualTo(2000.0);
    assertThat(meterRegistry.get("analytics.retention.runs.total").counter().count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.get("analytics.retention.errors.total").counter().count())
        .isEqualTo(0.0);
  }

  @Test
  @DisplayName("A purge failure is swallowed with an error metric (fail-open, schedule continues)")
  @TracesRequirement("REQ-ANALYTICS-005")
  void failureIsFailOpenWithErrorMetric() {
    when(mongoTemplate.remove(any(Query.class), anyString()))
        .thenThrow(new RuntimeException("mongo down"));

    purge.purge(); // must not throw

    assertThat(meterRegistry.get("analytics.retention.errors.total").counter().count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.get("analytics.retention.runs.total").counter().count())
        .isEqualTo(0.0);
  }

  @Test
  @DisplayName("Nothing to purge is a clean run")
  @TracesRequirement("REQ-ANALYTICS-005")
  void nothingToPurgeIsCleanRun() {
    when(mongoTemplate.remove(any(Query.class), anyString())).thenReturn(result(0));

    purge.purge();

    assertThat(meterRegistry.get("analytics.retention.purged.total").counter().count())
        .isEqualTo(0.0);
    assertThat(meterRegistry.get("analytics.retention.runs.total").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("Cutoff honors the configured retention window")
  @TracesRequirement("REQ-ANALYTICS-005")
  void cutoffHonorsRetentionDays() {
    Instant cutoff = purge.getCutoffInstant();

    long expectedSeconds = 90L * 86400L;
    long actual = Instant.now().getEpochSecond() - cutoff.getEpochSecond();
    // Allow a small slack for test execution time
    assertThat(actual).isBetween(expectedSeconds - 5, expectedSeconds + 5);
  }
}
