package com.demo.resortslite;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cz-java-0063: Redis-backed HTTP Session Configuration
 *
 * Replaces the default in-memory HttpSession store with Amazon ElastiCache (Redis)
 * via Spring Session. This ensures session state is:
 *   - Shared across all EKS pod replicas (horizontal scaling)
 *   - Durable across container restarts and pod rescheduling
 *   - Managed externally, independent of any single container lifecycle
 *
 * Redis connection is configured via environment variables:
 *   SPRING_REDIS_HOST  — ElastiCache primary endpoint (default: localhost)
 *   SPRING_REDIS_PORT  — ElastiCache port            (default: 6379)
 *
 * On EKS, inject these via a Kubernetes ConfigMap or Secret and use IRSA
 * (IAM Roles for Service Accounts) for secure, credential-free access to
 * ElastiCache within the same VPC.
 *
 * maxInactiveIntervalInSeconds: session TTL set to 30 minutes (1800 seconds).
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {
    // Spring Session auto-configures the Redis connection using
    // spring.redis.host / spring.redis.port from application.properties,
    // which are resolved from SPRING_REDIS_HOST / SPRING_REDIS_PORT env vars.
}
