package com.enterprise.mesh.bff.config;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The outbound HTTP client for everything the BFF calls.
 *
 * <p>Deliberately <em>not</em> built on the gateway's resilience machinery. The
 * gateway already owns the mesh's policy for this traffic: per-route circuit
 * breakers, the 5s time limiter, and the waypoint's retry and timeout rules. A
 * second, independently configured layer here would mean two places to change when
 * a latency budget moves, and two layers whose timeouts compose into something
 * nobody chose. What this service owns is the connect and read budget, so that a
 * hung gateway cannot pin a Tomcat thread until the pool drains.
 */
@Configuration
public class MeshClientConfig {

    private static final Logger log = LoggerFactory.getLogger(MeshClientConfig.class);

    /** Must match CorrelationIdFilter.HEADER in common-observability. */
    private static final String CORRELATION_HEADER = "X-Request-ID";

    @Bean
    public RestClient meshRestClient(RestClient.Builder builder, BffProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());

        log.info("BFF calls api-gateway at {} connectTimeout={} readTimeout={}",
                properties.getGatewayUri(), properties.getConnectTimeout(), properties.getReadTimeout());

        return builder
                .baseUrl(properties.getGatewayUri())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    // Propagate the correlation id so one shopper click stays one
                    // trace from the browser through the BFF into every service the
                    // aggregation touched. MDC is read per request rather than
                    // captured once, which is the whole reason CorrelationIdFilter
                    // puts it there.
                    String requestId = MDC.get("request_id");
                    if (requestId != null && !requestId.isBlank()) {
                        request.getHeaders().set(CORRELATION_HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
