package ca.tyny.urlshortener.infra.adapter.input.rest.dto;

import ca.tyny.urlshortener.core.model.CustomDomain;
import ca.tyny.urlshortener.core.model.DomainStatus;

import java.time.Instant;

public record DomainResponse(
        String host,
        DomainStatus status,
        String verificationToken,
        Instant createdAt) {

    public static DomainResponse from(CustomDomain domain) {
        return new DomainResponse(
                domain.host(),
                domain.status(),
                domain.verificationToken(),
                domain.createdAt());
    }
}