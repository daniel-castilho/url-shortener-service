package ca.tyny.urlshortener.core.idgeneration;

/**
 * Public interface of the URL ID generation module. The core service depends only on this
 * interface, without knowledge of the internal strategies.
 */
public interface UrlIdGenerator {
  String generateId(String customAlias, String userId);
}
