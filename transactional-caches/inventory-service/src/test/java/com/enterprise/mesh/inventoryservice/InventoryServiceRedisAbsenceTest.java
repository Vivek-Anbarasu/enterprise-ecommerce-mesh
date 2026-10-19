package com.enterprise.mesh.inventoryservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

/**
 * Guards the absence of Redis from this service.
 *
 * <p>This service once carried {@code spring-boot-starter-data-redis} and a
 * {@code spring.data.redis} block while containing no Redis code at all. The
 * starter was not merely inert. Because it was present, Boot contributed a
 * {@code redisHealthContributor}, and {@code /actuator/health} then attempted a
 * real connection to {@code ${REDIS_HOST:localhost}} - the pod itself in
 * Kubernetes - and answered:
 *
 * <pre>
 *   HTTP 503
 *   "redis": {"status":"DOWN","details":{"error":"RedisConnectionFailureException:
 *             Unable to connect to Redis"}},
 *   "status":"DOWN"
 * </pre>
 *
 * <p>while every other component was UP and the service was serving traffic
 * normally. The Kubernetes liveness and readiness probes did not catch it,
 * because {@code application.yml} scopes their groups to
 * {@code readinessState,db} and {@code livenessState}. So nothing restarted, no
 * deployment failed, and every external uptime monitor, status dashboard and
 * service-mesh health check was told the service was down. That is the worst
 * shape of outage: invisible to the orchestrator, visible to humans, and wrong.
 *
 * <p>Neither a stale config comment nor a unit test would have caught it, because
 * it only appears once a real context starts and someone reads the aggregate
 * health endpoint. Hence this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // H2 rather than PostgreSQL so the slice needs no container. MODE=PostgreSQL
        // keeps the DDL valid; Flyway is off so Hibernate creates the schema.
        "spring.datasource.url=jdbc:h2:mem:redis-absence;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "mesh.tenant.allow-all=true",
        "management.endpoint.health.show-details=always"
})
class InventoryServiceRedisAbsenceTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("no Redis bean exists, so nothing can be backed by one")
    void noRedisBeansInTheContext() {
        var redisBeans = java.util.Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> name.toLowerCase().contains("redis"))
                .sorted()
                .toList();

        assertThat(redisBeans)
                .as("A Redis starter on the classpath auto-configures a connection factory, "
                        + "templates and a health contributor, none of which this service uses. "
                        + "The health contributor is the part that matters: it reports DOWN "
                        + "against localhost and makes /actuator/health return 503 while the "
                        + "service is healthy. See the note in application.yml.")
                .isEmpty();
    }

    @Test
    @DisplayName("aggregate health is not dragged DOWN by an unused Redis client")
    void aggregateHealthIsNotDown() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/actuator/health")).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("The scoped liveness/readiness groups hid this, so Kubernetes never saw it. "
                        + "This assertion covers the aggregate endpoint that operators and uptime "
                        + "monitors actually read.")
                .isEqualTo(200);
        assertThat(response.body())
                .as("No component, and no aggregate status, may reference Redis.")
                .doesNotContain("redis");
    }
}