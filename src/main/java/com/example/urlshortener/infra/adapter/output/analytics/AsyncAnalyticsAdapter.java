package com.example.urlshortener.infra.adapter.output.analytics;

import com.example.urlshortener.core.model.ClickEvent;
import com.example.urlshortener.core.ports.outgoing.AnalyticsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class AsyncAnalyticsAdapter implements AnalyticsPort {

    private static final Logger log = LoggerFactory.getLogger(AsyncAnalyticsAdapter.class);
    private final BlockingQueue<ClickEvent> eventQueue;

    public AsyncAnalyticsAdapter() {
        // Capacity of 100k events to handle bursts
        this.eventQueue = new LinkedBlockingQueue<>(100_000);
    }

    @Override
    public void track(ClickEvent event) {
        // Fire-and-forget: Add to queue and return immediately
        // Using offer() to avoid blocking the http thread if queue is full
        if (!eventQueue.offer(event)) {
            log.warn("Analytics queue full! Dropping event for ID: {}", event.shortCode());
        }
    }

    public BlockingQueue<ClickEvent> getQueue() {
        return eventQueue;
    }
}
