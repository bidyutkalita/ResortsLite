package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AWS cloud-native configuration.
 *
 * Provides Spring beans for:
 *  - Amazon S3 (replaces local file system — blockers 1-7)
 *  - AWS Secrets Manager (replaces hard-coded DB credentials — blockers 8, 9, 18)
 *  - AWS Systems Manager Parameter Store (replaces hard-coded URLs/ports — blockers 10-12)
 *  - Amazon ElastiCache for Redis via RedisTemplate (replaces in-memory cache — blocker 20)
 */
@Configuration
public class AwsCloudConfig {

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client — used by ReportService to upload/download reports.
     * Replaces java.io.File and hard-coded /var/legacy/reports/ path.
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client — used by BookingService to retrieve
     * database credentials and user authentication secrets at runtime.
     * Eliminates hard-coded DB_USER / DB_PASS constants.
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Systems Manager (SSM) client — used by ReportService to retrieve
     * environment-specific URLs from Parameter Store.
     * Replaces hard-coded "http://reports.resorts-internal.com:8080" URL.
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * RedisTemplate configured for Amazon ElastiCache.
     * Used by BookingController to cache booking data with TTL,
     * replacing the unbounded static HashMap (blocker-20 cr-java-0067).
     * Spring Session also uses this Redis connection for distributed
     * HTTP session storage (blockers 13-17 cr-java-0065).
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
