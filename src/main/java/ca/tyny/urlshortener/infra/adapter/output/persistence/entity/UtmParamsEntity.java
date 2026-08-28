package ca.tyny.urlshortener.infra.adapter.output.persistence.entity;

import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Embedded document for UTM parameters.
 * Stored as a sub-document in {@link ShortUrlEntity}.
 */
public class UtmParamsEntity {

    @Field("source")
    private String source;

    @Field("medium")
    private String medium;

    @Field("campaign")
    private String campaign;

    @Field("term")
    private String term;

    @Field("content")
    private String content;

    public UtmParamsEntity() {
    }

    public UtmParamsEntity(String source, String medium, String campaign, String term, String content) {
        this.source = source;
        this.medium = medium;
        this.campaign = campaign;
        this.term = term;
        this.content = content;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getMedium() {
        return medium;
    }

    public void setMedium(String medium) {
        this.medium = medium;
    }

    public String getCampaign() {
        return campaign;
    }

    public void setCampaign(String campaign) {
        this.campaign = campaign;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}