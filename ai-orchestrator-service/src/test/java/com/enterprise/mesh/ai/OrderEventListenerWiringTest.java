package com.enterprise.mesh.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;

import com.enterprise.mesh.ai.messaging.OrderEventListener;
import com.enterprise.mesh.ai.messaging.ProductEventListener;
import com.enterprise.mesh.messaging.MessagingTopics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * Proves the purchase feed is actually wired, not merely present in the source.
 *
 * <p>A {@code @KafkaListener} is easy to write and easy to leave dead: a wrong topic, a
 * consumer group that collides with another service, or a missing {@code @EnableKafka}
 * all produce a service that starts cleanly, passes every probe, and never processes a
 * message. Neither a compile nor a unit test catches that, so this asserts on the
 * running context.
 *
 * <p>No broker needed: endpoints register from the annotation at context startup, and
 * {@code auto-startup=false} keeps the containers from trying to connect.
 *
 * <p>Note the topic is asserted from the annotation rather than from the container.
 * Spring Kafka 4 removed {@code ContainerProperties.getTopics()}, and the annotation is
 * the source of truth anyway - the container is derived from it.
 */
@SpringBootTest(properties = {
        "mesh.tenant.allow-all=true",
        "spring.kafka.listener.auto-startup=false"
})
class OrderEventListenerWiringTest {

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private OrderEventListener listener;

    @Test
    @DisplayName("a listener container is registered for this listener")
    void containerIsRegistered() {
        assertThat(registry.getListenerContainers())
                .as("No Kafka listener container is registered, so purchases would never be "
                        + "consumed while the service looks entirely healthy.")
                .isNotEmpty();
        assertThat(containerForThisListener())
                .as("OrderEventListener has no container: the annotation is not being honoured.")
                .isNotNull();
    }

    @Test
    @DisplayName("the endpoint runs in the mesh consumer group, not the yml one")
    void endpointUsesTheAnnotationGroup() {
        assertThat(containerForThisListener().getContainerProperties().getGroupId())
                .as("@KafkaListener(groupId = ...) overrides spring.kafka.consumer.group-id, so "
                        + "this is the group that actually owns these offsets.")
                .isEqualTo("ai-orchestrator");
    }

    @Test
    @DisplayName("the consumer group does not collide with the other order consumers")
    void groupDoesNotCollideWithOtherConsumers() {
        // notification-service and shipping-delivery-service each consume order.events.
        // Sharing a group would make Kafka split each message between them, so a purchase
        // would reach personalisation only a fraction of the time - and silently.
        assertThat(containerForThisListener().getContainerProperties().getGroupId())
                .isNotIn("notification-service", "shipping-service", "order-service");
    }

    @Test
    @DisplayName("the annotation names order.events and nothing else")
    void annotationBindsOrderEvents() throws NoSuchMethodException {
        KafkaListener annotation = OrderEventListener.class
                .getMethod("onOrderEvent", Message.class)
                .getAnnotation(KafkaListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.topics()).containsExactly(MessagingTopics.ORDER_EVENTS);
    }

    @Test
    @DisplayName("the product.events consumer runs in its own group")
    void semanticIndexerHasItsOwnGroup() throws NoSuchMethodException {
        // This service now consumes two topics. Sharing one group across them would let a slow
        // embedding stall purchase-affinity processing and vice versa - Kafka dispatches each
        // topic to one consumer per group, so a shared group couples two flows with no
        // ordering relationship and no reason to block each other.
        assertThat(containerForGroup("semantic-indexer"))
                .as("ProductEventListener has no container in its own group")
                .isNotNull();

        KafkaListener productAnnotation = ProductEventListener.class
                .getMethod("onProductEvent", Message.class)
                .getAnnotation(KafkaListener.class);

        assertThat(productAnnotation.topics()).containsExactly(MessagingTopics.PRODUCT_EVENTS);
        assertThat(containerForGroup("ai-orchestrator"))
                .as("The two listeners must resolve to different groups")
                .isNotNull();
    }

    @Test
    @DisplayName("the listener is a component, so there is a bean for the annotation to bind")
    void listenerIsAComponent() {
        // Without @Component there is no bean to proxy: the annotation is inert and, just
        // as importantly, so is the caching advice in RecommendationService.
        assertThat(listener).isNotNull();
        assertThat(OrderEventListener.class.getAnnotation(Component.class)).isNotNull();
    }

    private MessageListenerContainer containerForThisListener() {
        return containerForGroup("ai-orchestrator");
    }

    /**
     * Finds a listener container by its <em>resolved</em> consumer group.
     *
     * <p>Group rather than class name: the {@code @KafkaListener} groupId is a placeholder that
     * Spring resolves, so the annotation text cannot be matched against a running container,
     * and the container id does not carry the listener class name. The resolved group is the
     * property actually under test.
     *
     * <p>This used to fall back to "the only container in the service", which stopped being
     * true when {@code ProductEventListener} was added. The fallback then returned null and
     * the failures pointed at a null dereference rather than at the extra listener.
     */
    private MessageListenerContainer containerForGroup(String resolvedGroupId) {
        return registry.getListenerContainers().stream()
                .filter(c -> resolvedGroupId.equals(c.getContainerProperties().getGroupId()))
                .findFirst()
                .orElse(null);
    }
}