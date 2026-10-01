package com.demo.resortslite;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * AwsCloudConfig — Spring configuration class that registers AWS SDK v2 clients
 * and Redis infrastructure beans required for cloud-native operation.
 *
 * <p>All clients use the default credential provider chain, which resolves
 * credentials in the following order in an AWS environment:
 * <ol>
 *   <li>ECS task IAM role (recommended for ECS deployments)</li>
 *   <li>EC2 instance profile (for Elastic Beanstalk / EC2)</li>
 *   <li>Environment variables {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}</li>
 *   <li>AWS credentials file (local development only)</li>
 * </ol>
 *
 * <p>No credentials are hard-coded in this class or anywhere in the application.
 */
@Configuration
public class AwsCloudConfig {

    /** AWS region — injected from environment variable AWS_REGION or application property. */
    @Value("${cloud.aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // -----------------------------------------------------------------------
    // Amazon S3 client — used by ReportService for durable report storage
    // (replaces local java.io.File / FileWriter operations: cr-java-0061,
    //  cr-java-0062, cr-java-0063)
    // -----------------------------------------------------------------------

    /**
     * Amazon S3 client bean.
     *
     * @return configured {@link S3Client} instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    // -----------------------------------------------------------------------
    // AWS Secrets Manager client — used by BookingService to retrieve
    // database credentials at runtime (replaces hard-coded DB_USER / DB_PASS:
    // cr-java-0069, cr-java-0090)
    // -----------------------------------------------------------------------

    /**
     * AWS Secrets Manager client bean.
     *
     * @return configured {@link SecretsManagerClient} instance
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    // -----------------------------------------------------------------------
    // AWS Systems Manager (SSM) client — used by ReportService to resolve
    // environment-specific URLs and configuration from Parameter Store
    // (replaces hard-coded URLs and ports: cr-java-0071, cr-java-0077)
    // -----------------------------------------------------------------------

    /**
     * AWS Systems Manager SSM client bean.
     *
     * @return configured {@link SsmClient} instance
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    // -----------------------------------------------------------------------
    // Redis template — used by BookingController for distributed caching with
    // TTL (replaces unbounded in-memory HashMap: cr-java-0067).
    // Spring Session uses its own Redis connection; this template is for the
    // explicit booking cache.
    // -----------------------------------------------------------------------

    /**
     * Redis template bean with JSON serialization for booking cache entries.
     * Uses {@link GenericJackson2JsonRedisSerializer} so that complex objects
     * (Maps, POJOs) are stored as JSON strings in Redis.
     *
     * @param connectionFactory Spring Data Redis connection factory (auto-configured
     *                          from spring.redis.* properties pointing to ElastiCache)
     * @return configured {@link RedisTemplate}
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

    /**
     * Shared ObjectMapper bean for JSON serialization across the application.
     *
     * @return configured {@link ObjectMapper}
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
