package ca.tyny.urlshortener.core.ports.incoming;

import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.model.AnalyticsUnit;
import ca.tyny.urlshortener.core.model.ClicksSeries;
import java.time.LocalDate;

/** Use case for reading a link's click statistics — owner-scoped. */
public interface GetClickAnalyticsUseCase {

  /**
   * Returns the time series (+ breakdown) of clicks for a link the user owns.
   *
   * @param userId the authenticated user's ID
   * @param id the short URL code
   * @param unit DAY or HOUR granularity
   * @param from inclusive range start (UTC date); {@code null} defaults to 29 days before {@code
   *     to}
   * @param to inclusive range end (UTC date); {@code null} defaults to today
   * @throws UrlNotFoundException if the link does not exist
   * @throws ForbiddenException if the user is not the owner
   * @throws IllegalArgumentException for an invalid range (from after to, or hourly range larger
   *     than 30 days)
   */
  ClicksSeries get(String userId, String id, AnalyticsUnit unit, LocalDate from, LocalDate to)
      throws UrlNotFoundException, ForbiddenException;
}
