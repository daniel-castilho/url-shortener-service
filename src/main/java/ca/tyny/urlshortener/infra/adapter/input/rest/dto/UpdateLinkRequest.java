package ca.tyny.urlshortener.infra.adapter.input.rest.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UpdateLinkRequest {

    private String originalUrl;
    private String title;
    private List<@Size(min = 1, max = 50) @Pattern(regexp = "[a-z0-9_-]+") String> tags;
    private UtmParamsRequest utm;
    private Instant expiresAt;

    @JsonIgnore
    private final Map<String, Object> suppliedFields = new LinkedHashMap<>();

    public UpdateLinkRequest() {}

    @JsonAnySetter
    public void setSuppliedField(String key, Object value) {
        suppliedFields.put(key, value);
    }

    @JsonIgnore
    public boolean isFieldSupplied(String key) {
        return suppliedFields.containsKey(key);
    }

    public String getOriginalUrl() { return originalUrl; }
    public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public UtmParamsRequest getUtm() { return utm; }
    public void setUtm(UtmParamsRequest utm) { this.utm = utm; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public static class UtmParamsRequest {
        private String source;
        private String medium;
        private String campaign;
        private String term;
        private String content;

        public UtmParamsRequest() {}
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getMedium() { return medium; }
        public void setMedium(String medium) { this.medium = medium; }
        public String getCampaign() { return campaign; }
        public void setCampaign(String campaign) { this.campaign = campaign; }
        public String getTerm() { return term; }
        public void setTerm(String term) { this.term = term; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}