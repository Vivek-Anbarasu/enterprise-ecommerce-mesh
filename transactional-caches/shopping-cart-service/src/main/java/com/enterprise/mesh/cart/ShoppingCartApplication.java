package com.enterprise.mesh.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Session cart service.
 *
 * <p>Records are serialized with Jackson rather than JDK serialization so that
 * cart entries survive a class rename during a rolling deploy - otherwise every
 * live cart would become undecodable the moment a field was added.
 */
@SpringBootApplication
public class ShoppingCartApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShoppingCartApplication.class, args);
    }

    @Bean
    public RedisTemplate<String, ShoppingCartService.CartSnapshot> cartRedisTemplate(
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, ShoppingCartService.CartSnapshot> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(GenericJacksonJsonRedisSerializer.create(b -> { }));
        template.setHashValueSerializer(GenericJacksonJsonRedisSerializer.create(b -> { }));
        template.afterPropertiesSet();
        return template;
    }
}
