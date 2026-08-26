package ca.tyny.urlshortener.core.ports.outgoing;

import ca.tyny.urlshortener.core.model.ClickEvent;

public interface AnalyticsPort {
    void track(ClickEvent event);
}
