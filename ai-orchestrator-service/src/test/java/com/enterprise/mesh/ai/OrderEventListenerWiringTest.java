package com.enterprise.mesh.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;

import com.enterprise.mesh.ai.messaging.OrderEventListener;
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
    @DisplayName("the listener is a component, so there is a bean for the annotation to bind")
    void listenerIsAComponent() {
        // Without @Component there is no bean to proxy: the annotation is inert and, just
        // as importantly, so is the caching advice in RecommendationService.
        assertThat(listener).isNotNull();
        assertThat(OrderEventListener.class.getAnnotation(Component.class)).isNotNull();
    }

    private MessageListenerContainer containerForThisListener() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        return containers.stream()
                .filter(c -> c.getListenerId().contains(OrderEventListener.class.getSimpleName()))
                .findFirst()
                // Only one listener exists in this service, so an unlabelled container is
                // still this one - and failing to find it at all is the thing worth catching.
                .orElse(containers.size() == 1 ? containers.iterator().next() : null);
    }
}