package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

/**
 * AWS Secrets Manager configuration for database credentials.
 *
 * Replaces hard-coded DB_USER / DB_PASS constants (cr-java-0069) with
 * credentials fetched at startup from AWS Secrets Manager.  The secret is
 * expected to be a JSON object with at least the keys "username" and "password",
 * matching the default format produced by RDS-managed secrets:
 *
 * <pre>
 * {
 *   "username": "admin",
 *   "password": "Resort$Pass#2019!",
 *   "host":     "db-prod.resorts-internal.com",
 *   "port":     "5432",
 *   "dbname":   "resortdb"
 * }
 * </pre>
 *
 * Override the secret name and AWS region via environment variables or
 * application.properties:
 *   AWS_DB_SECRET_NAME  (default: resortslite/db/credentials)
 *   AWS_REGION          (default: us-east-1)
 */
@Configuration
public class AwsSecretsManagerConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsSecretsManagerConfig.class);

    /** Name / ARN of the Secrets Manager secret that holds DB credentials. */
    @Value("${aws.secretsmanager.db.secretName:${AWS_DB_SECRET_NAME:resortslite/db/credentials}}")
    private String dbSecretName;

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // -----------------------------------------------------------------------
    // SecretsManagerClient bean (reused by other components if needed)
    // -----------------------------------------------------------------------

    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    // -----------------------------------------------------------------------
    // DbCredentials bean — fetched once at startup, never stored in source code
    // -----------------------------------------------------------------------

    /**
     * Fetches the database credentials from AWS Secrets Manager and exposes
     * them as a {@link DbCredentials} value object.  Downstream beans
     * (e.g. DataSource configuration) should inject this bean rather than
     * reading hard-coded strings.
     */
    @Bean
    public DbCredentials dbCredentials(SecretsManagerClient secretsManagerClient) {
        log.info("Fetching database credentials from AWS Secrets Manager secret: {}", dbSecretName);

        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(dbSecretName)
                .build();

        GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
        String secretJson = response.secretString();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(secretJson);

            String username = node.path("username").asText();
            String password = node.path("password").asText();
            String host     = node.path("host").asText(
                    System.getenv().getOrDefault("DB_HOST", "localhost"));
            String port     = node.path("port").asText("5432");
            String dbName   = node.path("dbname").asText("resortdb");

            log.info("Database credentials retrieved successfully for user '{}' on host '{}'",
                    username, host);

            return new DbCredentials(username, password, host, port, dbName);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse database credentials from AWS Secrets Manager secret '"
                            + dbSecretName + "': " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Inner value object
    // -----------------------------------------------------------------------

    /**
     * Immutable holder for database connection credentials retrieved from
     * AWS Secrets Manager.  No credential values are stored in source code.
     */
    public static class DbCredentials {

        private final String username;
        private final String password;
        private final String host;
        private final String port;
        private final String dbName;

        public DbCredentials(String username, String password,
                             String host, String port, String dbName) {
            this.username = username;
            this.password = password;
            this.host     = host;
            this.port     = port;
            this.dbName   = dbName;
        }

        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public String getHost()     { return host; }
        public String getPort()     { return port; }
        public String getDbName()   { return dbName; }

        /** Convenience JDBC URL builder for common RDBMS drivers. */
        public String buildJdbcUrl(String driverScheme) {
            return String.format("jdbc:%s://%s:%s/%s", driverScheme, host, port, dbName);
        }
    }
}
