package com.mouse.profiler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Provides a dedicated executor for CSV ingestion tasks.
 *
 * <p><b>Why a separate executor?</b>
 * CSV ingestion is I/O-intensive (streaming file reads) and DB-intensive (batch inserts of
 * up to 500,000 rows). Running ingestion on the shared HTTP worker threads would starve
 * concurrent query requests and violate the Stage 4B requirement that uploads must not
 * degrade query performance.
 *
 * <p><b>Why only 2 core threads?</b>
 * Railway compute is limited. Two threads allow two concurrent uploads while leaving the
 * majority of CPU capacity for query handling. A third concurrent upload queues rather than
 * failing — the bounded queue has capacity for a small backlog.
 *
 * <p>No message queue (e.g., Kafka, RabbitMQ) is used. The workload does not justify the
 * operational overhead of a separate broker for what is fundamentally a bounded concurrency
 * problem solvable with a thread pool.
 */
@Configuration
public class CsvIngestionConfig {

    /**
     * Bounded thread pool for CSV ingestion.
     *
     * <ul>
     *   <li>Core threads: 2 — always alive, ready to process uploads immediately.</li>
     *   <li>Max threads: 2 — hard cap; excess uploads queue rather than spawn threads.</li>
     *   <li>Queue capacity: 10 — allows a small backlog of pending uploads.</li>
     *   <li>Rejection policy: CallerRunsPolicy would block the HTTP thread; AbortPolicy throws
     *       immediately. We use AbortPolicy and catch it in the controller to return a 429.</li>
     * </ul>
     */
    @Bean(name ="csvIngestionExecutor")
    public Executor csvIngestionExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                60L, TimeUnit.SECONDS,      // keepAliveTime (irrelevant at max==core)
                new LinkedBlockingQueue<>(10),
                r -> {
                    Thread t = new Thread(r, "csv-ingestion-%d".formatted(System.nanoTime()));
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy() // throws RejectedExecutionException if queue full
        );
    }
}
