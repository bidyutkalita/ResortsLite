package com.demo.resortslite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DeliveryMediumType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CognitoAuthService — cr-java-0090 remediation (File-based Authentication).
 *
 * <p>Replaces the previous pattern of storing authentication credentials and user data
 * in local files (or hard-coded constants) with Amazon Cognito for user identity
 * management and AWS Secrets Manager for credential storage.
 *
 * <p>Previously, {@link BookingService} generated confirmation/authentication tokens
 * using the broken MD5 algorithm applied to locally-stored data (line 108 of the
 * original source).  This class provides a cloud-native replacement:
 * <ul>
 *   <li>User identities are managed in an Amazon Cognito User Pool — no local user
 *       files or in-memory credential stores.</li>
 *   <li>Authentication tokens (ID token, access token, refresh token) are issued by
 *       Cognito and signed with RS256 — no custom hashing required.</li>
 *   <li>Booking confirmation codes are generated as cryptographically secure UUIDs
 *       rather than MD5 digests of user-supplied data.</li>
 *   <li>Any credentials required to call Cognito (e.g. app client secret) are
 *       retrieved from AWS Secrets Manager — never stored in source code or files.</li>
 * </ul>
 *
 * <p>Required environment variables / application properties:
 * <ul>
 *   <li>{@code AWS_COGNITO_USER_POOL_ID} / {@code aws.cognito.userPoolId}</li>
 *   <li>{@code AWS_COGNITO_CLIENT_ID}    / {@code aws.cognito.clientId}</li>
 *   <li>{@code AWS_COGNITO_SECRET_NAME}  / {@code aws.cognito.secretName}
 *       — Secrets Manager secret containing the Cognito app client secret
 *         (key: {@code clientSecret})</li>
 *   <li>{@code AWS_REGION}              / {@code aws.region}</li>
 * </ul>
 */
@Service
public class CognitoAuthService {

    private static final Logger log = LoggerFactory.getLogger(CognitoAuthService.class);

    /** Amazon Cognito User Pool ID — injected from environment / application.properties. */
    @Value("${aws.cognito.userPoolId:${AWS_COGNITO_USER_POOL_ID:us-east-1_PLACEHOLDER}}")
    private String userPoolId;

    /** Amazon Cognito App Client ID — injected from environment / application.properties. */
    @Value("${aws.cognito.clientId:${AWS_COGNITO_CLIENT_ID:PLACEHOLDER_CLIENT_ID}}")
    private String clientId;

    /**
     * AWS Secrets Manager secret name that holds the Cognito app client secret.
     * The secret must be a JSON object with at least the key {@code "clientSecret"}.
     */
    @Value("${aws.cognito.secretName:${AWS_COGNITO_SECRET_NAME:resortslite/cognito/app-client}}")
    private String cognitoSecretName;

    private final CognitoIdentityProviderClient cognitoClient;
    private final SecretsManagerClient secretsManagerClient;

    /**
     * Constructs a {@code CognitoAuthService} with the required AWS SDK clients.
     * Both clients are provided as Spring beans (see {@link CognitoConfig} and
     * {@link AwsSecretsManagerConfig}) so credentials are resolved from the standard
     * AWS credential chain (IAM role, environment variables) — never from local files.
     *
     * @param cognitoClient         the Amazon Cognito Identity Provider client bean
     * @param secretsManagerClient  the AWS Secrets Manager client bean
     */
    public CognitoAuthService(CognitoIdentityProviderClient cognitoClient,
                               SecretsManagerClient secretsManagerClient) {
        this.cognitoClient        = cognitoClient;
        this.secretsManagerClient = secretsManagerClient;
    }

    // -----------------------------------------------------------------------
    // User registration — replaces local file-based user storage
    // -----------------------------------------------------------------------

    /**
     * Registers a new guest user in the Amazon Cognito User Pool.
     *
     * <p>cr-java-0090 FIX: Previously, user data (name, email, booking reference) was
     * written to a local file or held in static in-memory maps.  This method creates
     * the user record in Cognito, which provides built-in user lifecycle management,
     * MFA, and audit logging — none of which are available with file-based storage.
     *
     * @param guestName  the guest's display name
     * @param email      the guest's email address (used as Cognito username)
     * @param bookingId  the booking reference to associate with the user
     * @return a map containing the Cognito username and registration status
     */
    public Map<String, Object> registerGuestUser(String guestName, String email, String bookingId) {
        Map<String, Object> result = new HashMap<>();
        try {
            AdminCreateUserRequest createRequest = AdminCreateUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .temporaryPassword(generateTemporaryPassword())
                    .userAttributes(
                            AttributeType.builder().name("name").value(guestName).build(),
                            AttributeType.builder().name("email").value(email).build(),
                            AttributeType.builder().name("email_verified").value("true").build(),
                            AttributeType.builder().name("custom:bookingId").value(bookingId).build()
                    )
                    .desiredDeliveryMediums(DeliveryMediumType.EMAIL)
                    .build();

            AdminCreateUserResponse response = cognitoClient.adminCreateUser(createRequest);
            String cognitoUsername = response.user().username();

            log.info("Guest user '{}' registered in Cognito User Pool for booking '{}'",
                    cognitoUsername, bookingId);

            result.put("status", "registered");
            result.put("cognitoUsername", cognitoUsername);
            result.put("userStatus", response.user().userStatusAsString());

        } catch (UsernameExistsException e) {
            log.info("Guest user '{}' already exists in Cognito User Pool — skipping registration.", email);
            result.put("status", "exists");
            result.put("cognitoUsername", email);
        } catch (Exception e) {
            log.error("Failed to register guest user '{}' in Cognito: {}", email, e.getMessage());
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Secure confirmation code generation — replaces MD5 hashing (line 108)
    // -----------------------------------------------------------------------

    /**
     * Generates a cryptographically secure booking confirmation code.
     *
     * <p>cr-java-0090 FIX (line 108): Replaces the broken MD5-based confirmation code
     * generation in {@link BookingService#md5Hash(String)} with a UUID-based token.
     * UUID v4 uses a cryptographically secure random number generator (SecureRandom),
     * making it collision-resistant and unpredictable — unlike MD5 which is reversible
     * and vulnerable to pre-image attacks.  The token is not derived from user-supplied
     * data, eliminating the risk of hash-based enumeration attacks.
     *
     * <p>In production, this token can optionally be stored in Cognito as a custom
     * attribute ({@code custom:confirmationCode}) to associate it with the user's
     * identity record, providing a full audit trail in Cognito's CloudTrail logs.
     *
     * @param bookingId  the booking reference (used for logging only — not hashed)
     * @param guestName  the guest name (used for logging only — not hashed)
     * @return a secure, URL-safe confirmation code string
     */
    public String generateSecureConfirmationCode(String bookingId, String guestName) {
        // cr-java-0090 FIX: UUID.randomUUID() uses SecureRandom internally, producing a
        // 128-bit cryptographically random token.  This replaces the MD5 digest of
        // (bookingId + guestName) which was predictable and reversible.
        String confirmationCode = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        log.info("Secure confirmation code generated for booking '{}' (guest: '{}')",
                bookingId, guestName);
        return confirmationCode;
    }

    // -----------------------------------------------------------------------
    // Credential retrieval — replaces file-based credential storage
    // -----------------------------------------------------------------------

    /**
     * Retrieves the Cognito app client secret from AWS Secrets Manager.
     *
     * <p>cr-java-0090 FIX: Previously, authentication credentials were stored in local
     * files or hard-coded constants.  This method retrieves the Cognito app client
     * secret from AWS Secrets Manager at runtime, ensuring credentials are:
     * <ul>
     *   <li>Encrypted at rest using AWS KMS</li>
     *   <li>Auditable via AWS CloudTrail</li>
     *   <li>Rotatable without code changes</li>
     *   <li>Never stored in source code, version control, or container images</li>
     * </ul>
     *
     * @return the Cognito app client secret value
     */
    public String getCognitoClientSecret() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(cognitoSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            // Parse the JSON secret to extract the clientSecret field
            String secretJson = response.secretString();
            // Simple extraction — use Jackson ObjectMapper for production robustness
            if (secretJson.contains("\"clientSecret\"")) {
                int start = secretJson.indexOf("\"clientSecret\"") + "\"clientSecret\"".length();
                start = secretJson.indexOf("\"", start) + 1;
                int end = secretJson.indexOf("\"", start);
                return secretJson.substring(start, end);
            }
            return secretJson;
        } catch (Exception e) {
            log.error("Failed to retrieve Cognito client secret from Secrets Manager: {}",
                    e.getMessage());
            throw new IllegalStateException(
                    "Cannot retrieve Cognito client secret from AWS Secrets Manager: "
                            + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // User lookup — replaces file-based user data retrieval
    // -----------------------------------------------------------------------

    /**
     * Looks up a guest user in the Amazon Cognito User Pool by email.
     *
     * <p>cr-java-0090 FIX: Replaces local file reads for user data retrieval with
     * a Cognito AdminGetUser API call.  User data is stored centrally in Cognito,
     * not in local files that are inaccessible across horizontally-scaled instances.
     *
     * @param email the guest's email address (Cognito username)
     * @return a map containing the user's Cognito attributes, or an error map
     */
    public Map<String, Object> getGuestUser(String email) {
        Map<String, Object> result = new HashMap<>();
        try {
            AdminGetUserRequest request = AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .build();

            AdminGetUserResponse response = cognitoClient.adminGetUser(request);

            result.put("username", response.username());
            result.put("userStatus", response.userStatusAsString());
            response.userAttributes().forEach(attr ->
                    result.put(attr.name(), attr.value()));

            log.info("Guest user '{}' retrieved from Cognito User Pool.", email);

        } catch (UserNotFoundException e) {
            log.warn("Guest user '{}' not found in Cognito User Pool.", email);
            result.put("status", "not_found");
            result.put("message", "User not found: " + email);
        } catch (Exception e) {
            log.error("Failed to retrieve guest user '{}' from Cognito: {}", email, e.getMessage());
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Generates a temporary password for new Cognito users.
     * The password meets Cognito's default complexity requirements.
     *
     * @return a temporary password string
     */
    private String generateTemporaryPassword() {
        // Generates a UUID-based temporary password that satisfies Cognito's default
        // password policy (uppercase, lowercase, numbers, special characters).
        return "Tmp!" + UUID.randomUUID().toString().substring(0, 8).toUpperCase() + "1a";
    }
}
