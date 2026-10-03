package ca.tyny.urlshortener.infra.adapter.input.rest.mapper;

import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.infra.adapter.input.rest.ShortLinkBaseUrlResolver;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortUrlResponse;
import java.util.List;
import java.util.stream.Collectors;

public class LinkMapper {

  public ShortUrlResponse toResponse(ShortUrl domain, ShortLinkBaseUrlResolver baseUrlResolver) {
    if (domain == null) {
      return null;
    }

    ShortUrlResponse.UtmParamsResponse utmResponse = null;
    if (domain.utm() != null) {
      utmResponse =
          new ShortUrlResponse.UtmParamsResponse(
              domain.utm().source(),
              domain.utm().medium(),
              domain.utm().campaign(),
              domain.utm().term(),
              domain.utm().content());
    }

    return new ShortUrlResponse(
        domain.id(),
        domain.originalUrl(),
        baseUrlResolver.baseFor(domain) + "/" + domain.id(),
        domain.createdAt(),
        domain.userId(),
        domain.isCustomAlias(),
        domain.clickCount(),
        domain.expiresAt(),
        domain.title(),
        domain.tags(),
        utmResponse,
        domain.deletedAt(),
        domain.domain());
  }

  public List<ShortUrlResponse> toResponseList(
      List<ShortUrl> domains, ShortLinkBaseUrlResolver baseUrlResolver) {
    if (domains == null) {
      return List.of();
    }
    return domains.stream().map(d -> toResponse(d, baseUrlResolver)).collect(Collectors.toList());
  }
}
