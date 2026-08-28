package ca.tyny.urlshortener.infra.adapter.input.rest.dto;

import java.util.List;

public record DomainListResponse(
        List<DomainResponse> domains) {
}