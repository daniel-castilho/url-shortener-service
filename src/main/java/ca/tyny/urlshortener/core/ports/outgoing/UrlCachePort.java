package ca.tyny.urlshortener.core.ports.outgoing;

import ca.tyny.urlshortener.core.model.CachedUrlValue;

public interface UrlCachePort {

    CachedUrlValue get(String id);

    void put(String id, CachedUrlValue value);
}