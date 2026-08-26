package ca.tyny.urlshortener.core.ports.incoming;

public interface GetUrlUseCase {
    String getOriginalUrl(String id);
}
