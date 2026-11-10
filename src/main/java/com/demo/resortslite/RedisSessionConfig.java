package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cz-java-0063: Spring Session Redis Configuration
 *
 * Replaces in-memory HttpSession with Spring Session backed by Amazon ElastiCache for Redis.
 * All session data is stored externally in Redis, enabling:
 *   - Horizontal scaling across multiple EKS pod replicas (AWS EKS)
 *   - Session persistence across pod restarts and container failures
 *   - Consistent session state regardless of which pod handles the request
 *
 * cz-java-0070: RedisTemplate bean is also provided here for use by BookingController
 * to replace the local in-memory HashMap cache with a shared ElastiCache-backed cache,
 * ensuring cache consistency across all horizontally-scaled EKS pod replicas.
 *
 * Connection parameters are injected via environment variables (Kubernetes Secret / EKS Pod spec):
 *   REDIS_HOST                — ElastiCache primary endpoint
 *                               (e.g. my-cluster.abc123.ng.0001.use1.cache.amazonaws.com)
 *   REDIS_PORT                — ElastiCache port (default: 6379)
 *   SESSION_TIMEOUT_SECONDS   — Max inactive interval for sessions (default: 1800 = 30 minutes)
 *
 * IRSA (IAM Roles for Service Accounts) is used for secure, credential-free access to
 * ElastiCache from EKS pods — no static AWS credentials are stored in the application.
 */
@Configuration
@EnableRedisHttpSession
public class RedisSessionConfig {

    /**
     * Redis host injected from REDIS_HOST environment variable.
     * Maps to Amazon ElastiCache primary endpoint in EKS deployments.
     */
    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    /**
     * Redis port injected from REDIS_PORT environment variable.
     * Default is 6379 (standard ElastiCache port).
     */
    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    /**
     * Configures the Redis connection factory using Amazon ElastiCache endpoint
     * injected via environment variables. No hardcoded hostnames or ports.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cz-java-0070: RedisTemplate bean for distributed cache operations.
     *
     * Provides a type-safe RedisTemplate used by BookingController to store and retrieve
     * booking cache entries in Amazon ElastiCache (Redis), replacing the former local
     * in-memory HashMap that was invisible to other EKS pod replicas.
     *
     * Uses StringRedisSerializer for keys and GenericJackson2JsonRedisSerializer for values
     * to ensure correct serialization of Map<String, Object> booking entries.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
