package ca.tyny.urlshortener.infra.adapter.input.rest.advice;

import ca.tyny.urlshortener.core.exception.AliasAlreadyExistsException;
import ca.tyny.urlshortener.core.exception.CodeGenerationException;
import ca.tyny.urlshortener.core.exception.DomainAlreadyExistsException;
import ca.tyny.urlshortener.core.exception.DomainNotFoundException;
import ca.tyny.urlshortener.core.exception.DomainNotVerifiedException;
import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.InvalidDestinationException;
import ca.tyny.urlshortener.core.exception.InvalidDomainException;
import ca.tyny.urlshortener.core.exception.InvalidExpiryException;
import ca.tyny.urlshortener.core.exception.QuotaExceededException;
import ca.tyny.urlshortener.core.exception.UrlExpiredException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.infra.adapter.output.persistence.exception.RepositoryException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Centralized exception handling for REST endpoints.
 *
 * <p>Maps domain exceptions to appropriate HTTP responses with appropriate status codes. All error
 * responses follow a consistent format via {@link ErrorResponse} and {@link
 * ValidationErrorResponse}.
 *
 * <p>Security note (CWE-117): All exception messages containing client-controlled data (ids,
 * aliases, URLs) are sanitized via {@link #logSafe(String)} before logging to prevent log injection
 * attacks (CWE-117). The {@link #logSafe(String)} method replaces newline and carriage return
 * characters with underscores.
 *
 * <p>Exception mapping:
 *
 * <ul>
 *   <li>{@link UrlNotFoundException} → 404 Not Found
 *   <li>{@link UrlExpiredException} → 410 Gone
 *   <li>{@link AliasAlreadyExistsException} → 409 Conflict
 *   <li>{@link DomainAlreadyExistsException} → 409 Conflict
 *   <li>{@link DomainNotFoundException} → 404 Not Found
 *   <li>{@link InvalidDestinationException} → 400 Bad Request
 *   <li>{@link InvalidExpiryException} → 400 Bad Request
 *   <li>{@link InvalidExpiryException} → 400 Bad Request
 *   <li>{@link ForbiddenException} → 403 Forbidden
 *   <li>{@link QuotaExceededException} → 402 Payment Required
 *   <li>{@link AliasAlreadyExistsException} → 409 Conflict
 *   <li>{@link DomainAlreadyExistsException} → 409 Conflict
 *   <li>{@link DomainNotFoundException} → 404 Not Found
 *   <li>{@link InvalidDomainException} → 400 Bad Request
 *   <li>{@link InvalidDomainException} → 400 Bad Request
 *   <li>{@link DomainNotVerifiedException} → 400 Bad Request
 *   <li>{@link QuotaExceededException} → 402 Payment Required
 *   <li>{@link CodeGenerationException} → 500 Internal Server Error
 *   <li>{@link InvalidDestinationException} → 400 Bad Request
 *   <li>{@link MethodArgumentNotValidException} → 400 Bad Request (with field errors)
 *   <li>{@link MethodArgumentTypeMismatchException} → 400 Bad Request
 *   <li>{@link CallNotPermittedException} → 503 Service Unavailable (circuit breaker)
 *   <li>{@link DataAccessException} → 503 Service Unavailable
 *   <li>{@link RepositoryException} → 503 Service Unavailable
 *   <li>{@link Exception} → 500 Internal Server Error (catch-all)
 * </ul>
 *
 * <p>All error responses follow a consistent format via {@link ErrorResponse} and {@link
 * ValidationErrorResponse} records, including status code, error type, message, and timestamp.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  // CWE-117 defense at the sink (lessons.md §Security): exception messages embed
  // client-controlled ids/aliases/URLs, so a forged id with \n/\r could forge log lines.
  // CodeQL-recognized sanitizer: replace(char, char) on \n and \r, in the logging method.
  static String logSafe(String value) {
    return value == null ? null : value.replace('\n', '_').replace('\r', '_');
  }

  @ExceptionHandler(UrlNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleUrlNotFound(UrlNotFoundException ex) {
    log.warn("URL not found: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.NOT_FOUND.value(), "URL Not Found", ex.getMessage(), LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
  }

  @ExceptionHandler(UrlExpiredException.class)
  public ResponseEntity<ErrorResponse> handleUrlExpired(UrlExpiredException ex) {
    log.warn("URL expired: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.GONE.value(), "URL Expired", ex.getMessage(), LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.GONE).body(error);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    log.warn("Invalid argument: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "Invalid Request",
            ex.getMessage(),
            LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  @ExceptionHandler(InvalidDestinationException.class)
  public ResponseEntity<ErrorResponse> handleInvalidDestination(InvalidDestinationException ex) {
    log.warn("Invalid destination URL: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "Invalid Destination",
            ex.getMessage(),
            LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  @ExceptionHandler(InvalidExpiryException.class)
  public ResponseEntity<ErrorResponse> handleInvalidExpiry(InvalidExpiryException ex) {
    log.warn("Invalid expiry: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(), "Invalid Expiry", ex.getMessage(), LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  @ExceptionHandler(ForbiddenException.class)
  public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex) {
    log.warn("Forbidden: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.FORBIDDEN.value(), "Forbidden", ex.getMessage(), LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
  }

  @ExceptionHandler(CodeGenerationException.class)
  public ResponseEntity<ErrorResponse> handleCodeGeneration(CodeGenerationException ex) {
    log.error("Code generation failed after {} attempts", ex.getAttempts());

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Code Generation Failed",
            "Unable to generate a unique short code. Please try again later.",
            LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
  }

  @ExceptionHandler(QuotaExceededException.class)
  public ResponseEntity<ErrorResponse> handleQuotaExceeded(QuotaExceededException ex) {
    log.warn("Quota exceeded: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.PAYMENT_REQUIRED.value(),
            "Quota Exceeded",
            ex.getMessage(),
            LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(error);
  }

  @ExceptionHandler(AliasAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleAliasAlreadyExists(AliasAlreadyExistsException ex) {
    log.warn("Alias already exists: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.CONFLICT.value(),
            "Alias Already Exists",
            ex.getMessage(),
            LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
  }

  @ExceptionHandler(DomainAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleDomainAlreadyExists(DomainAlreadyExistsException ex) {
    log.warn("Domain already claimed: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.CONFLICT.value(),
            "Domain Already Exists",
            ex.getMessage(),
            LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
  }

  @ExceptionHandler(DomainNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleDomainNotFound(DomainNotFoundException ex) {
    log.warn("Domain not found: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.NOT_FOUND.value(), "Domain Not Found", ex.getMessage(), LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
  }

  @ExceptionHandler(InvalidDomainException.class)
  public ResponseEntity<ErrorResponse> handleInvalidDomain(InvalidDomainException ex) {
    log.warn("Invalid domain: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(), "Invalid Domain", ex.getMessage(), LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  @ExceptionHandler(DomainNotVerifiedException.class)
  public ResponseEntity<ErrorResponse> handleDomainNotVerified(DomainNotVerifiedException ex) {
    log.warn("Domain not verified: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "Domain Not Verified",
            ex.getMessage(),
            LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ValidationErrorResponse> handleValidationErrors(
      MethodArgumentNotValidException ex) {
    log.warn("Validation failed: {}", logSafe(ex.getMessage()));

    Map<String, String> errors = new HashMap<>();
    ex.getBindingResult()
        .getAllErrors()
        .forEach(
            error -> {
              String fieldName =
                  error instanceof FieldError
                      ? ((FieldError) error).getField()
                      : error.getObjectName();
              String errorMessage = error.getDefaultMessage();
              errors.put(fieldName, errorMessage);
            });

    ValidationErrorResponse errorResponse =
        new ValidationErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "Validation Failed",
            "Request validation failed",
            LocalDateTime.now(),
            errors);

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    log.warn("Type mismatch: {}", logSafe(ex.getMessage()));

    String message =
        String.format("Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName());

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(), "Invalid Parameter Type", message, LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
    log.error("Unexpected error occurred", ex);

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Internal Server Error",
            "An unexpected error occurred. Please try again later.",
            LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
  }

  @ExceptionHandler(CallNotPermittedException.class)
  public ResponseEntity<ErrorResponse> handleCircuitBreakerOpen(CallNotPermittedException ex) {
    log.warn("Circuit breaker open: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            "Service Unavailable",
            "Service temporarily unavailable due to high load or dependency failure. Please try again later.",
            LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<ErrorResponse> handleDataAccessException(DataAccessException ex) {
    log.error("Data access failure: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            "Service Unavailable",
            "Service temporarily unavailable due to data store failure. Please try again later.",
            LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
  }

  @ExceptionHandler(RepositoryException.class)
  public ResponseEntity<ErrorResponse> handleRepositoryException(RepositoryException ex) {
    log.error("Persistence failure: {}", logSafe(ex.getMessage()));

    ErrorResponse error =
        new ErrorResponse(
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            "Service Unavailable",
            "Service temporarily unavailable due to data store failure. Please try again later.",
            LocalDateTime.now());

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
  }

  // Error response DTOs
  public record ErrorResponse(int status, String error, String message, LocalDateTime timestamp) {}

  public record ValidationErrorResponse(
      int status,
      String error,
      String message,
      LocalDateTime timestamp,
      Map<String, String> validationErrors) {}
}
