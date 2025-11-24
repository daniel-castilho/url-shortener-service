package com.example.urlshortener.infra.adapter.output.analytics;

import com.example.urlshortener.core.model.ClickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

@Component
public class ClickBatchWorker {

    private static final Logger log = LoggerFactory.getLogger(ClickBatchWorker.class);
    private final AsyncAnalyticsAdapter analyticsAdapter;

    // In a real scenario, we would inject a Repository to save these events
    // private final ClickRepository clickRepository;

    public ClickBatchWorker(AsyncAnalyticsAdapter analyticsAdapter) {
        this.analyticsAdapter = analyticsAdapter;
    }

    // Run every 5 seconds
    @Scheduled(fixedRate = 5000)
    public void processBatch() {
        BlockingQueue<ClickEvent> queue = analyticsAdapter.getQueue();
        if (queue.isEmpty()) {
            return;
        }

        List<ClickEvent> batch = new ArrayList<>();
        queue.drainTo(batch, 1000); // Drain up to 1000 events

        if (!batch.isEmpty()) {
            log.info("Processing batch of {} click events...", batch.size());
            // Here we would do a batch insert into MongoDB
            // clickRepository.saveAll(batch);

            // For now, just log to simulate processing
            batch.forEach(e -> log.debug("Processed click for {}", e.shortCode()));
        }
    }
}
