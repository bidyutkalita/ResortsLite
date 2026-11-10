package com.demo.resortslite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

/**
 * AWS Systems Manager (SSM) Parameter Store configuration.
 *
 * <p>Provides a shared {@link SsmClient} bean and a helper method for retrieving
 * parameter values from AWS SSM Parameter Store at runtime.  This replaces all
 * hard-coded environment-specific URLs (cr-java-0071) with externalized configuration
 * values that can be managed independently per deployment environment (dev / staging /
 * production) without any code changes.
 *
 * <p>Required environment variables / application properties:
 * <ul>
 *   <li>{@code AWS_REGION} / {@code aws.region} — AWS region (default: us-east-1)</li>
 * </ul>
 *
 * <p>Credentials are resolved automatically from the standard AWS credential chain:
 * <ol>
 *   <li>IAM instance / task role (recommended for EC2 / ECS / EKS deployments)</li>
 *   <li>Environment variables {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}</li>
 *   <li>AWS shared credentials file ({@code ~/.aws/credentials})</li>
 * </ol>
 */
@Configuration
public class AwsSsmConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsSsmConfig.class);

    /** AWS region resolved from the {@code aws.region} property or {@code AWS_REGION} env var. */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates and returns a singleton {@link SsmClient} configured for the target AWS region.
     *
     * @return a configured {@link SsmClient} instance
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Retrieves a plain-text or SecureString parameter value from AWS SSM Parameter Store.
     *
     * <p>SecureString parameters are automatically decrypted using the KMS key associated
     * with the parameter when {@code withDecryption} is set to {@code true}.
     *
     * @param ssmClient     the SSM client bean
     * @param parameterName the full SSM parameter path (e.g. {@code /resortslite/prod/inventory-url})
     * @param defaultValue  fallback value used when the parameter cannot be retrieved
     *                      (e.g. during local development without AWS credentials)
     * @return the resolved parameter value, or {@code defaultValue} on error
     */
    public static String getParameter(SsmClient ssmClient, String parameterName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            String value = response.parameter().value();
            log.info("SSM Parameter '{}' resolved successfully.", parameterName);
            return value;
        } catch (Exception e) {
            log.warn("Could not retrieve SSM parameter '{}': {}. Using default value.",
                    parameterName, e.getMessage());
            return defaultValue;
        }
    }
}
