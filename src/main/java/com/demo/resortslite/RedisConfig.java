package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cz-java-0070: Redis Cache Configuration
 *
 * Provides a RedisTemplate bean used by BookingController to store booking cache
 * entries in Amazon ElastiCache (Redis) instead of a local in-memory HashMap.
 *
 * This ensures cached data is:
 *   - Shared across all EKS pod replicas (horizontal scaling)
 *   - Durable across container restarts and pod rescheduling
 *   - Subject to TTL-based expiry to prevent stale data
 *
 * Redis connection is configured via environment variables:
 *   SPRING_REDIS_HOST  — ElastiCache primary endpoint (default: localhost)
 *   SPRING_REDIS_PORT  — ElastiCache port            (default: 6379)
 *
 * On EKS, inject these via a Kubernetes ConfigMap or Secret and use IRSA
 * (IAM Roles for Service Accounts) for secure, credential-free access to
 * ElastiCache within the same VPC.
 */
@Configuration
public class RedisConfig {

    /**
     * RedisTemplate configured with String keys and JSON-serialized Object values.
     * Used by BookingController to cache booking entries in Amazon ElastiCache.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Use String serializer for keys to keep cache keys human-readable
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use JSON serializer for values to support Map<String, Object> round-trips
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
