package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring configuration class that exposes an AWS SDK v2 {@link S3Client} bean.
 *
 * <p>This bean is required by {@link ReportService} as part of the cr-java-0061
 * remediation that replaces hard-coded absolute file-system paths with Amazon S3
 * object storage.
 *
 * <p>Credentials are resolved automatically from the standard AWS credential chain:
 * <ol>
 *   <li>IAM instance / task role (recommended for EC2 / ECS / EKS deployments)</li>
 *   <li>Environment variables {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}</li>
 *   <li>AWS shared credentials file ({@code ~/.aws/credentials})</li>
 * </ol>
 * No credentials are hard-coded in the application source.
 */
@Configuration
public class S3ClientConfig {

    /** AWS region resolved from the {@code aws.region} property or {@code AWS_REGION} env var. */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates and returns a singleton {@link S3Client} configured for the target AWS region.
     *
     * <p>Uses the lightweight {@link UrlConnectionHttpClient} to avoid pulling in the
     * Netty async HTTP client, keeping the dependency footprint minimal for a
     * Spring Boot 2.7 / Java 8 application.
     *
     * @return a configured {@link S3Client} instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }
}
