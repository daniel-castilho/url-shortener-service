package ca.tyny.urlshortener.core.exception;

public class DomainNotVerifiedException extends RuntimeException {

    private final String domain;

    public DomainNotVerifiedException(String domain) {
        super("Domain is not verified or not active: " + domain);
        this.domain = domain;
    }

    public String getDomain() {
        return domain;
    }
}