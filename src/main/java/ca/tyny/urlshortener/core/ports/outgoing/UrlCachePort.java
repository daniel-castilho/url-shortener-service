package ca.tyny.urlshortener.core.ports.outgoing;

import ca.tyny.urlshortener.core.model.CacheLookup;
import ca.tyny.urlshortener.core.model.CachedUrlValue;

public interface UrlCachePort {

    CacheLookup lookup(String id);

    void put(String id, CachedUrlValue value);
}