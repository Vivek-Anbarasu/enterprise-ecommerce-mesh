package com.enterprise.mesh.payment.provider;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Applies the provider's connect and read budget to the auto-configured
 * {@code RestClient.Builder}.
 *
 * <p>A {@link RestClientCustomizer} rather than {@code .requestFactory(...)} inside
 * {@link HttpPaymentGateway} itself, and that is deliberate. Boot applies every
 * customizer to the {@code RestClient.Builder} it exposes, so the gateway can simply
 * {@code build()} the injected builder and the timeouts are already in place. Keeping
 * the factory out of the gateway also means the class no longer overrides whatever
 * request factory it was handed, which is what lets a test bind
 * {@code MockRestServiceServer} to the same builder and assert on the actual wire
 * traffic.
 */
@Configuration
@Slf4j
public class PaymentProviderClientConfig {

    @Bean
    public RestClientCustomizer paymentProviderTimeouts(PaymentProviderProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());

        log.info("Payment provider HTTP budget: connectTimeout={} readTimeout={}",
                properties.getConnectTimeout(), properties.getReadTimeout());

        return builder -> builder.requestFactory(requestFactory);
    }
}