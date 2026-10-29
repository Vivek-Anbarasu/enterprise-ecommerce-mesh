package com.enterprise.mesh.order.saga;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the HTTP client used by the checkout saga.
 *
 * <p>The important line is the request factory: without an explicit connect and
 * read budget, a hung inventory or payment service blocks the caller forever.
 * Because the saga is synchronous, that also means the shopper's request never
 * completes. The budgets come from {@code mesh.saga.connect-timeout} and
 * {@code mesh.saga.read-timeout}.
 *
 * <p>A named bean rather than a plain injected builder, so the saga's client is
 * visibly separate from any other {@code RestClient} a future feature adds; the
 * saga must not inherit someone else's timeouts.
 */
@Configuration(proxyBeanMethods = false)
public class SagaClientConfig {

    @Bean
    RestClient sagaRestClient(RestClient.Builder builder, SagaProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        return builder.requestFactory(requestFactory).build();
    }
}
