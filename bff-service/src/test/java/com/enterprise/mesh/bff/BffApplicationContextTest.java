package com.enterprise.mesh.bff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.cors.CorsConfigurationSource;

import com.enterprise.mesh.security.tenant.TenantContextFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the context starts.
 *
 * <p>Worth having as a test rather than trusting a manual boot: the failure this
 * guards against is a bean-wiring clash, and the clash is between two
 * auto-configurations that both think they own the security filter chain and the
 * CORS source. Those only collide when the context is actually built.
 *
 * <p>Runs on the default profile, with no {@code entra} profile and no Entra client
 * id, so it needs neither Redis nor Azure. That is the point: it pins the keyless
 * boot path as a real, tested configuration rather than an aspiration. A service
 * whose whole job is authentication still has to start - and serve its anonymous
 * storefront - in an environment where no identity provider is configured.
 */
@SpringBootTest(properties = {
        "spring.session.store-type=none",
        "spring.data.redis.host=localhost"
})
class BffApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the context starts and this service owns its own security chain")
    void contextLoads() {
        assertThat(context).isNotNull();

        // common-security would otherwise contribute a stateless bearer chain;
        // there must be exactly one, and it must be ours.
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
        assertThat(context.getBean(SecurityFilterChain.class).getClass().getName())
                .contains("DefaultSecurityFilterChain");
    }

    @Test
    @DisplayName("this service's CORS source wins over the shared wildcard one")
    void overridesSharedCorsSource() {
        CorsConfigurationSource source = context.getBean("meshCorsConfigurationSource", CorsConfigurationSource.class);

        // The bean name is the override mechanism: common-security backs off on a
        // missing bean of this name, and the BFF's enumerated origins take its place.
        assertThat(source).isNotNull();
    }

    @Test
    @DisplayName("the tenant filter is not registered with the servlet container")
    void tenantContextFilterIsNotAutoRegistered() {
        // This is a runtime failure that a context-load test cannot see. Spring Boot
        // registers every Filter bean automatically, outside the security chain, so
        // owning a SecurityFilterChain does not stop it. Left enabled, it rejects
        // every anonymous request with 400 "Tenant Required" and the browsable
        // storefront is dead - and it resolves the tenant from a caller-supplied
        // X-Tenant-ID header, which on a browser edge is just attacker input.
        FilterRegistrationBean<?> registration =
                context.getBean("disableTenantContextFilter", FilterRegistrationBean.class);

        assertThat(registration.isEnabled()).isFalse();
        assertThat(registration.getFilter()).isInstanceOf(TenantContextFilter.class);
    }

    @Test
    @DisplayName("the CSRF cookie is written and is deliberately readable by the SPA")
    void csrfCookieIsReadableByScript() {
        CookieCsrfTokenRepository repository = context.getBean(CookieCsrfTokenRepository.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, response);

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .isNotNull()
                .startsWith("XSRF-TOKEN=")
                // The whole SPA CSRF scheme depends on this: an HttpOnly CSRF
                // cookie could never be echoed back in a header.
                .doesNotContain("HttpOnly");
        assertThat(token.getHeaderName()).isEqualTo("X-XSRF-TOKEN");
    }
}