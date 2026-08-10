package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Azure Cache for Redis configuration.
 *
 * Provides a RedisTemplate bean used by BookingController to replace:
 *  - In-memory HttpSession state (blockers 13-17)
 *  - In-memory HashMap cache without TTL (blocker 20)
 *
 * Connection details (host, port, password, SSL) are supplied via
 * environment variables / application.properties — no hard-coded values.
 */
@Configuration
public class AzureRedisConfig {

    /**
     * Configures a RedisTemplate with JSON serialization for storing
     * booking objects and session data in Azure Cache for Redis.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Use JSON serializer for values to support complex objects
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
