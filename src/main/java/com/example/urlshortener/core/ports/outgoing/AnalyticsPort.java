package com.example.urlshortener.core.ports.outgoing;

import com.example.urlshortener.core.model.ClickEvent;

public interface AnalyticsPort {
    void track(ClickEvent event);
}
