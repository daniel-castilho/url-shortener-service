package ca.tyny.urlshortener.infra.adapter.input.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record ClaimDomainRequest(
        @NotBlank(message = "host is required") String host) {
}