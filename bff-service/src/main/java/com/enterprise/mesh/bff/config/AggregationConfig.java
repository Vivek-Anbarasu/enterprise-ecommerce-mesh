package com.enterprise.mesh.bff.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Threading for the fan-out.
 *
 * <p>A virtual-thread-per-task executor rather than the common ForkJoinPool. The
 * work being dispatched is blocking HTTP to other services, which is precisely the
 * case virtual threads were made for: while one section waits on the cart service,
 * the carrier is free to start the orders request, and the cost of a few thousand
 * parked threads instead of a few hundred blocked platform threads is nothing.
 *
 * <p>Given the same executor to a blocking {@code RestClient} would pin carrier
 * threads and put the parallelism straight back.
 */
@Configuration
public class AggregationConfig {

    /**
     * Named so it can be injected by type; {@code destroyMethod} lets the JVM
     * close it on shutdown instead of leaving a non-daemon pool behind.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService aggregationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}