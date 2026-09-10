package ca.tyny.urlshortener.core.exception;

public class DomainAlreadyExistsException extends RuntimeException {

  public DomainAlreadyExistsException(String host) {
    super("Custom domain already claimed: " + host);
  }
}
