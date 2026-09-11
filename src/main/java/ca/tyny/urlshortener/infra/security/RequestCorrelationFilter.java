package ca.tyny.urlshortener.infra.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation-Id filter (Epic 3, story 3.1).
 *
 * <p>Accepts an inbound {@code X-Request-Id} only when it is a safe ASCII token ({@code
 * [A-Za-z0-9._-]}, 1..64 chars); otherwise a random {@link UUID} is generated. The resolved id is
 * echoed on the response {@code X-Request-Id} header and published to the SLF4J MDC as {@code
 * request_id} for the duration of the request, so every log line written on the request thread
 * carries it (log patterns include {@code %X{request_id}} / JSON {@code includeMdcKeyName
 * request_id}).
 *
 * <p>Runs before the Spring Security filter chain ({@link Ordered#HIGHEST_PRECEDENCE}), so id
 * correlation also covers authentication and actuator requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

  static final String REQUEST_ID_HEADER = "X-Request-Id";
  static final String MDC_KEY = "request_id";

  private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = request.getHeader(REQUEST_ID_HEADER);
    if (requestId == null || !SAFE_ID.matcher(requestId).matches()) {
      requestId = UUID.randomUUID().toString();
    }
    response.setHeader(REQUEST_ID_HEADER, requestId);
    MDC.put(MDC_KEY, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
