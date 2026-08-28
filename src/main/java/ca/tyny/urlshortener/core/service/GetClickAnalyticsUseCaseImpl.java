package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.model.AnalyticsUnit;
import ca.tyny.urlshortener.core.model.ClicksSeries;
import ca.tyny.urlshortener.core.ports.incoming.GetClickAnalyticsUseCase;
import ca.tyny.urlshortener.core.ports.incoming.GetLinkUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.ClickAnalyticsPort;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

public class GetClickAnalyticsUseCaseImpl implements GetClickAnalyticsUseCase {

    /** Hourly series must remain cheap: cap the width of the requested range. */
    static final int MAX_HOUR_RANGE_DAYS = 30;

    private final GetLinkUseCase getLinkUseCase;
    private final ClickAnalyticsPort clickAnalyticsPort;

    public GetClickAnalyticsUseCaseImpl(GetLinkUseCase getLinkUseCase,
            ClickAnalyticsPort clickAnalyticsPort) {
        this.getLinkUseCase = getLinkUseCase;
        this.clickAnalyticsPort = clickAnalyticsPort;
    }

    @Override
    public ClicksSeries get(String userId, String id, AnalyticsUnit unit, LocalDate from, LocalDate to)
            throws UrlNotFoundException, ForbiddenException {
        // Ownership + existence check first: 404 then 403 semantics.
        getLinkUseCase.get(userId, id);

        LocalDate effectiveTo = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(MAX_HOUR_RANGE_DAYS - 1);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }

        List<ClicksSeries.Bucket> series = switch (unit) {
            case DAY -> clickAnalyticsPort.daily(id, effectiveFrom, effectiveTo);
            case HOUR -> hourlyBounded(id, effectiveFrom, effectiveTo);
        };
        Map<String, Map<String, Long>> breakdown =
                clickAnalyticsPort.breakdown(id, effectiveFrom, effectiveTo);

        long total = series.stream().mapToLong(ClicksSeries.Bucket::clicks).sum();
        Instant startExclusive = effectiveFrom.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toExclusive = effectiveTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return new ClicksSeries(id, unit.name().toLowerCase(), startExclusive, toExclusive,
                total, series, breakdown);
    }

    private List<ClicksSeries.Bucket> hourlyBounded(String shortCode, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_HOUR_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "Hourly series supports ranges of at most " + MAX_HOUR_RANGE_DAYS + " days");
        }
        Instant startInclusive = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endExclusive = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return clickAnalyticsPort.hourly(shortCode, startInclusive, endExclusive);
    }
}