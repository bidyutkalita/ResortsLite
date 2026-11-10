package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cr-java-0065 FIX — HTTP Session State Storage
 *
 * Replaces the default in-memory HTTP session store with Amazon ElastiCache for Redis
 * using Spring Session.  All session attributes written via HttpSession.setAttribute()
 * are transparently serialised to the shared Redis cluster, making every application
 * instance fully stateless.
 *
 * Benefits in AWS:
 *  - No sticky sessions (ALB target-group stickiness can be disabled)
 *  - Sessions survive EC2 instance termination / Auto Scaling scale-in events
 *  - Sessions are shared across all instances in the cluster — horizontal scaling works
 *  - ElastiCache Multi-AZ replication provides high availability for session data
 *
 * Configuration is driven entirely by environment variables / application.properties:
 *  - REDIS_HOST  (or spring.redis.host)  — ElastiCache primary endpoint
 *  - REDIS_PORT  (or spring.redis.port)  — default 6379
 *  - SESSION_TIMEOUT_SECONDS             — default 1800 (30 minutes)
 *
 * In production, inject REDIS_HOST from the ECS task definition / EKS ConfigMap /
 * Elastic Beanstalk environment properties pointing at the ElastiCache cluster endpoint.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    // ElastiCache primary endpoint — injected from environment variable REDIS_HOST
    // or application.properties key spring.redis.host.
    @Value("${spring.redis.host:${REDIS_HOST:localhost}}")
    private String redisHost;

    // ElastiCache port — default 6379 (standard Redis port).
    @Value("${spring.redis.port:${REDIS_PORT:6379}}")
    private int redisPort;

    /**
     * Lettuce-based Redis connection factory pointing at the ElastiCache cluster.
     * Lettuce is the default client used by Spring Data Redis and supports both
     * standalone and cluster modes without additional configuration.
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }
}
