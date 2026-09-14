package ca.tyny.urlshortener.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.model.AnalyticsUnit;
import ca.tyny.urlshortener.core.model.ClicksSeries;
import ca.tyny.urlshortener.core.ports.incoming.GetLinkUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.ClickAnalyticsPort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetClickAnalyticsUseCaseImpl unit tests")
class GetClickAnalyticsUseCaseTest {

  @Mock private GetLinkUseCase getLinkUseCase;

  @Mock private ClickAnalyticsPort clickAnalyticsPort;

  private GetClickAnalyticsUseCaseImpl useCase;

  @Test
  @DisplayName("Throws 404 when link not found")
  @TracesRequirement("REQ-ANALYTICS-004")
  void throws404WhenNotFound() {
    when(getLinkUseCase.get("user1", "abc123")).thenThrow(new UrlNotFoundException("abc123"));
    useCase = new GetClickAnalyticsUseCaseImpl(getLinkUseCase, clickAnalyticsPort);

    assertThatThrownBy(() -> useCase.get("user1", "abc123", AnalyticsUnit.DAY, null, null))
        .isInstanceOf(UrlNotFoundException.class);
  }

  @Test
  @DisplayName("Throws 403 when user is not the owner")
  @TracesRequirement("REQ-ANALYTICS-004")
  void throws403WhenNotOwner() {
    when(getLinkUseCase.get("user1", "abc123")).thenThrow(new ForbiddenException("not owner"));
    useCase = new GetClickAnalyticsUseCaseImpl(getLinkUseCase, clickAnalyticsPort);

    assertThatThrownBy(() -> useCase.get("user1", "abc123", AnalyticsUnit.DAY, null, null))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("Returns daily series from port and sums total")
  @TracesRequirement("REQ-ANALYTICS-004")
  void returnsDailySeries() {
    when(getLinkUseCase.get(anyString(), anyString())).thenReturn(null);
    List<ClicksSeries.Bucket> daily =
        List.of(
            new ClicksSeries.Bucket(
                LocalDate.now(ZoneOffset.UTC).minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(),
                5L),
            new ClicksSeries.Bucket(
                LocalDate.now(ZoneOffset.UTC).minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                3L));
    when(clickAnalyticsPort.daily(anyString(), any(), any())).thenReturn(daily);
    when(clickAnalyticsPort.breakdown(anyString(), any(), any())).thenReturn(Map.of());

    useCase = new GetClickAnalyticsUseCaseImpl(getLinkUseCase, clickAnalyticsPort);
    ClicksSeries result = useCase.get("user1", "abc123", AnalyticsUnit.DAY, null, null);

    assertThat(result.series()).hasSize(2);
    assertThat(result.totalClicks()).isEqualTo(8);
    assertThat(result.unit()).isEqualTo("day");
  }

  @Test
  @DisplayName("Returns hourly series from port when unit=HOUR")
  @TracesRequirement("REQ-ANALYTICS-004")
  void returnsHourlySeries() {
    when(getLinkUseCase.get(anyString(), anyString())).thenReturn(null);
    List<ClicksSeries.Bucket> hourly = List.of(new ClicksSeries.Bucket(Instant.now(), 2L));
    when(clickAnalyticsPort.hourly(anyString(), any(), any())).thenReturn(hourly);
    when(clickAnalyticsPort.breakdown(anyString(), any(), any())).thenReturn(Map.of());

    useCase = new GetClickAnalyticsUseCaseImpl(getLinkUseCase, clickAnalyticsPort);
    ClicksSeries result = useCase.get("user1", "abc123", AnalyticsUnit.HOUR, null, null);

    assertThat(result.unit()).isEqualTo("hour");
    assertThat(result.series()).hasSize(1);
  }

  @Test
  @DisplayName("Includes unique counts for day unit")
  @TracesRequirement("REQ-ANALYTICS-004")
  void includesUniqueCountsForDay() {
    when(getLinkUseCase.get(anyString(), anyString())).thenReturn(null);
    List<ClicksSeries.Bucket> daily =
        List.of(
            new ClicksSeries.Bucket(
                LocalDate.now(ZoneOffset.UTC).minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                5L));
    when(clickAnalyticsPort.daily(anyString(), any(), any())).thenReturn(daily);
    when(clickAnalyticsPort.breakdown(anyString(), any(), any())).thenReturn(Map.of());
    when(clickAnalyticsPort.uniquePerDay(anyString(), any(), any()))
        .thenReturn(Map.of("2026-08-27", 42L));

    useCase = new GetClickAnalyticsUseCaseImpl(getLinkUseCase, clickAnalyticsPort);
    ClicksSeries result = useCase.get("user1", "abc123", AnalyticsUnit.DAY, null, null);

    assertThat(result.uniquePerBucket()).isNotNull();
    assertThat(result.uniquePerBucket().get("2026-08-27")).isEqualTo(42L);
  }

  @Test
  @DisplayName("Unique counts are null for hour unit")
  @TracesRequirement("REQ-ANALYTICS-004")
  void uniqueCountsNullForHour() {
    when(getLinkUseCase.get(anyString(), anyString())).thenReturn(null);
    List<ClicksSeries.Bucket> hourly = List.of(new ClicksSeries.Bucket(Instant.now(), 2L));
    when(clickAnalyticsPort.hourly(anyString(), any(), any())).thenReturn(hourly);
    when(clickAnalyticsPort.breakdown(anyString(), any(), any())).thenReturn(Map.of());

    useCase = new GetClickAnalyticsUseCaseImpl(getLinkUseCase, clickAnalyticsPort);
    ClicksSeries result = useCase.get("user1", "abc123", AnalyticsUnit.HOUR, null, null);

    assertThat(result.uniquePerBucket()).isNull();
  }

  @Test
  @DisplayName("Throws when hourly range exceeds 30 days")
  @TracesRequirement("REQ-ANALYTICS-004")
  void rejectsWideHourlyRange() {
    when(getLinkUseCase.get(anyString(), anyString())).thenReturn(null);
    useCase = new GetClickAnalyticsUseCaseImpl(getLinkUseCase, clickAnalyticsPort);

    LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(31);
    LocalDate to = LocalDate.now(ZoneOffset.UTC);

    assertThatThrownBy(() -> useCase.get("user1", "abc123", AnalyticsUnit.HOUR, from, to))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("30 days");
  }

  @Test
  @DisplayName("Throws when from is after to")
  @TracesRequirement("REQ-ANALYTICS-004")
  void rejectsInvalidRange() {
    when(getLinkUseCase.get(anyString(), anyString())).thenReturn(null);
    useCase = new GetClickAnalyticsUseCaseImpl(getLinkUseCase, clickAnalyticsPort);

    LocalDate from = LocalDate.now(ZoneOffset.UTC);
    LocalDate to = from.minusDays(1);

    assertThatThrownBy(() -> useCase.get("user1", "abc123", AnalyticsUnit.DAY, from, to))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'from' must not be after 'to'");
  }
}
