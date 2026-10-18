package com.enterprise.mesh.messaging.config;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.FixedBackOff;

import com.enterprise.mesh.messaging.MessagingTopics;
import com.enterprise.mesh.messaging.publisher.EventPublisher;
import com.enterprise.mesh.messaging.publisher.KafkaEventPublisher;

/**
 * Kafka defaults for the mesh: JSON payloads, keyed partitioning, at-least-once
 * delivery with bounded retries, and a dead-letter topic for poison messages.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
@EnableKafka
@EnableConfigurationProperties(MeshMessagingProperties.class)
@Slf4j
public class MeshMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProducerFactory<String, Object> meshProducerFactory(KafkaProperties kafkaProperties,
                                                               MeshMessagingProperties meshProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);

        // Durability over latency: an order must not vanish because of a transient leader change.
        config.putIfAbsent(ProducerConfig.ACKS_CONFIG, "all");
        config.putIfAbsent(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.putIfAbsent(ProducerConfig.RETRIES_CONFIG, 5);
        config.putIfAbsent(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.putIfAbsent(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        config.putIfAbsent(ProducerConfig.LINGER_MS_CONFIG, 5);
        // Bound how long a broker outage may block a request thread.
        config.putIfAbsent(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                (int) meshProperties.getDeliveryTimeout().toMillis());
        config.putIfAbsent(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                (int) meshProperties.getRequestTimeout().toMillis());

        log.info("Kafka producer configured bootstrap={} acks={} idempotence={}",
                config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG),
                config.get(ProducerConfig.ACKS_CONFIG),
                config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsumerFactory<String, Object> meshConsumerFactory(KafkaProperties kafkaProperties,
                                                               MeshMessagingProperties meshProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);

        // Only our own packages may be instantiated from a consumed payload.
        config.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.enterprise.mesh");
        config.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.putIfAbsent(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, meshProperties.getMaxPollRecords());

        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    @ConditionalOnMissingBean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate,
            MeshMessagingProperties meshProperties) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(meshProperties.getConsumerConcurrency());
        // Manual acknowledgement: the offset advances only after the handler returns.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setIdleBetweenPolls(meshProperties.getIdleBetweenPolls().toMillis());

        // Retry transiently, then divert to <topic>.dlq so one bad message cannot
        // block the partition forever.
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(kafkaTemplate,
                        (record, exception) -> new TopicPartition(
                                MessagingTopics.deadLetter(record.topic()), record.partition()));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(meshProperties.getRetryInterval().toMillis(),
                        meshProperties.getMaxRetries()));

        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                MessageConversionException.class,
                IllegalArgumentException.class);

        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("Kafka message failed topic={} partition={} offset={} attempt={} error={}",
                        record.topic(), record.partition(), record.offset(),
                        deliveryAttempt, exception.toString()));

        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        return new KafkaEventPublisher(kafkaTemplate);
    }

    /**
     * Optional topic bootstrap for dev and for the very first AKS bring-up.
     * Production topics must be managed declaratively (Terraform) instead, which
     * is why this stays off by default.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "mesh.messaging", name = "auto-create-topics", havingValue = "true")
    static class TopicBootstrap {

        @Bean
        public KafkaAdmin.NewTopics meshTopics(MeshMessagingProperties properties) {
            log.warn("mesh.messaging.auto-create-topics=true - topics will be created at runtime. "
                    + "Manage topics declaratively in production.");
            // Only topics with a producer and a consumer. This list previously named six
            // topics that nothing published to and nothing consumed, so the broker was
            // materialising real topics for flows that did not exist.
            return new KafkaAdmin.NewTopics(
                    topic(properties, MessagingTopics.ORDER_EVENTS),
                    topic(properties, MessagingTopics.PRODUCT_EVENTS));
        }

        private NewTopic topic(MeshMessagingProperties properties, String name) {
            TopicBuilder builder = TopicBuilder.name(name)
                    .partitions(properties.getPartitions())
                    .replicas(properties.getReplicas());
            // TopicBuilder.config() accepts a single key/value pair per call.
            properties.getTopicConfigs().forEach(builder::config);
            return builder.build();
        }
    }
}