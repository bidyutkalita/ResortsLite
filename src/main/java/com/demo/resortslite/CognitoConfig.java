package com.demo.resortslite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * CognitoConfig — cr-java-0090 remediation (File-based Authentication).
 *
 * <p>Provides the {@link CognitoIdentityProviderClient} Spring bean used by
 * {@link CognitoAuthService} to interact with Amazon Cognito User Pools.
 *
 * <p>Credentials are resolved automatically from the standard AWS credential chain
 * (IAM instance/task role, environment variables, ~/.aws/credentials) — no credentials
 * are stored in source code, configuration files, or local files.
 *
 * <p>Required environment variables / application properties:
 * <ul>
 *   <li>{@code AWS_REGION} / {@code aws.region} — AWS region (default: us-east-1)</li>
 *   <li>{@code AWS_COGNITO_USER_POOL_ID} / {@code aws.cognito.userPoolId}</li>
 *   <li>{@code AWS_COGNITO_CLIENT_ID}    / {@code aws.cognito.clientId}</li>
 *   <li>{@code AWS_COGNITO_SECRET_NAME}  / {@code aws.cognito.secretName}</li>
 * </ul>
 */
@Configuration
public class CognitoConfig {

    private static final Logger log = LoggerFactory.getLogger(CognitoConfig.class);

    /** AWS region resolved from the {@code aws.region} property or {@code AWS_REGION} env var. */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates and returns a singleton {@link CognitoIdentityProviderClient} configured
     * for the target AWS region.
     *
     * <p>The client uses the default AWS credential provider chain, which resolves
     * credentials in the following order:
     * <ol>
     *   <li>IAM instance role (EC2) or task role (ECS/EKS) — recommended for production</li>
     *   <li>Environment variables {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}</li>
     *   <li>AWS shared credentials file ({@code ~/.aws/credentials}) — local development only</li>
     * </ol>
     *
     * @return a configured {@link CognitoIdentityProviderClient} instance
     */
    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient() {
        log.info("Initialising Amazon Cognito Identity Provider client for region '{}'", awsRegion);
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
