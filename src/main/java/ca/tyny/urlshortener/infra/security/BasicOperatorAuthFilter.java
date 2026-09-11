package ca.tyny.urlshortener.infra.security;

import ca.tyny.urlshortener.infra.config.properties.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP Basic authentication for the infrastructure operator (debt 26).
 *
 * <p>Coexists with the JWT filter: this filter reacts only to an {@code Authorization: Basic}
 * header (the JWT filter reacts only to {@code Bearer}). The operator account is injected via
 * environment ({@code OPERATOR_USERNAME} / {@code OPERATOR_PASSWORD} — see {@code
 * SecurityProperties.Operator}) and grants {@code ROLE_OPERATOR} over the actuator tiers only
 * (enforced by the {@code SecurityFilterChain} matchers). A wrong pair yields 401 through the
 * shared entry point; when no operator is configured, any Basic attempt fails closed.
 *
 * <p>Deliberately NOT a Spring bean (Spring Security reference, "Declaring Your Filter as a Bean"):
 * bean filters get registered with the embedded container AND with the security chain, causing
 * double invocation — and {@code @WebMvcTest} slices would drag {@code SecurityProperties} into the
 * context. It is instantiated inside {@code SecurityConfig#securityFilterChain} via {@code
 * addFilterBefore} instead.
 */
public class BasicOperatorAuthFilter extends OncePerRequestFilter {

  static final String OPERATOR_ROLE = "ROLE_OPERATOR";

  private final SecurityProperties.Operator operator;

  public BasicOperatorAuthFilter(SecurityProperties securityProperties) {
    this.operator = securityProperties.operator();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Basic ")) {
      try {
        authenticate(header.substring(6), request);
      } catch (AuthenticationException ex) {
        // Challenge the caller; the shared entry point answers 401
        SecurityContextHolder.clearContext();
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid operator credentials");
        return;
      }
    }
    filterChain.doFilter(request, response);
  }

  private void authenticate(String base64Credentials, HttpServletRequest request) {
    if (!operator.isConfigured()) {
      throw new BadCredentialsException("No operator account is configured");
    }
    String decoded =
        new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
    int sep = decoded.indexOf(':');
    if (sep < 0) {
      throw new BadCredentialsException("Malformed Basic credentials");
    }
    String username = decoded.substring(0, sep);
    String password = decoded.substring(sep + 1);
    if (!constantTimeEquals(username, operator.username())
        || !constantTimeEquals(password, operator.password())) {
      throw new BadCredentialsException("Invalid operator credentials");
    }

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            username, null, List.of(new SimpleGrantedAuthority(OPERATOR_ROLE)));
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private static boolean constantTimeEquals(String a, String b) {
    return java.security.MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
