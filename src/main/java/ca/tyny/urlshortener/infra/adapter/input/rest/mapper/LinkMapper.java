package ca.tyny.urlshortener.infra.adapter.input.rest.mapper;

import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.model.UtmParams;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortUrlResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class LinkMapper {

    public ShortUrlResponse toResponse(ShortUrl domain, String baseUrl) {
        if (domain == null) {
            return null;
        }

        ShortUrlResponse.UtmParamsResponse utmResponse = null;
        if (domain.utm() != null) {
            utmResponse = new ShortUrlResponse.UtmParamsResponse(
                    domain.utm().source(),
                    domain.utm().medium(),
                    domain.utm().campaign(),
                    domain.utm().term(),
                    domain.utm().content());
        }

        return new ShortUrlResponse(
                domain.id(),
                domain.originalUrl(),
                baseUrl + "/" + domain.id(),
                domain.createdAt(),
                domain.userId(),
                domain.isCustomAlias(),
                domain.clickCount(),
                domain.expiresAt(),
                domain.title(),
                domain.tags(),
                utmResponse,
                domain.deletedAt());
    }

    public List<ShortUrlResponse> toResponseList(List<ShortUrl> domains, String baseUrl) {
        if (domains == null) {
            return List.of();
        }
        return domains.stream()
                .map(d -> toResponse(d, baseUrl))
                .collect(Collectors.toList());
    }
}