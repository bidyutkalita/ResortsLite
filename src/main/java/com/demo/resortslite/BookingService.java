package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

@Service
public class BookingService {

    private static final Logger logger = Logger.getLogger(BookingService.class.getName());

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0021: DB_HOST externalised to environment variable / Parameter Store
    private static final String DB_HOST = System.getenv("DB_HOST") != null
            ? System.getenv("DB_HOST")
            : "localhost";

    // cr-java-0069 FIX: DB_USER and DB_PASS are no longer hard-coded.
    // Credentials are retrieved at startup from AWS Secrets Manager using the
    // secret name supplied via the DB_SECRET_NAME environment variable.
    // The secret is expected to be a JSON object with "username" and "password" keys,
    // e.g.: {"username":"admin","password":"Resort$Pass#2019!"}
    private String dbUser;
    private String dbPass;

    // AWS region for Secrets Manager and Cognito — defaults to us-east-1 if not set
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // Secret name / ARN stored in an environment variable — never hard-coded
    @Value("${db.secret.name:${DB_SECRET_NAME:resortslite/db/credentials}}")
    private String dbSecretName;

    // cr-java-0090 FIX: Amazon Cognito User Pool ID for guest identity validation.
    // The User Pool ID is injected via the COGNITO_USER_POOL_ID environment variable
    // or the cognito.user.pool.id application property (sourced from AWS Parameter Store).
    // This replaces any file-based user/credential storage with cloud-native identity management.
    @Value("${cognito.user.pool.id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Fetches database credentials from AWS Secrets Manager on application startup.
     * This replaces the previously hard-coded DB_USER and DB_PASS constants and
     * satisfies rule cr-java-0069 (Hard-coded Database Credentials).
     *
     * The secret must be stored in AWS Secrets Manager as a JSON string:
     *   {"username": "<db-user>", "password": "<db-password>"}
     *
     * Required environment variables:
     *   DB_SECRET_NAME  – name or ARN of the secret in AWS Secrets Manager
     *   AWS_REGION      – AWS region where the secret is stored (default: us-east-1)
     */
    @PostConstruct
    public void loadDatabaseCredentialsFromSecretsManager() {
        try {
            SecretsManagerClient secretsClient = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = secretsClient.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretNode = mapper.readTree(secretJson);

            this.dbUser = secretNode.get("username").asText();
            this.dbPass = secretNode.get("password").asText();

            logger.info("Database credentials successfully loaded from AWS Secrets Manager "
                    + "for secret: " + dbSecretName);

            secretsClient.close();
        } catch (Exception e) {
            // Log the error without exposing credential values; rethrow to prevent
            // the application from starting with missing/invalid credentials.
            logger.severe("Failed to load database credentials from AWS Secrets Manager "
                    + "(secret: " + dbSecretName + "): " + e.getMessage());
            throw new IllegalStateException(
                    "Cannot start application: database credentials unavailable from "
                    + "AWS Secrets Manager. Ensure DB_SECRET_NAME and AWS_REGION are set "
                    + "and the IAM role has secretsmanager:GetSecretValue permission.", e);
        }
    }

    // cr-java-0090 FIX: Validates the guest identity against Amazon Cognito User Pool
    // before allowing a booking to be created. This replaces any file-based user lookup
    // or local credential store with a cloud-native identity check via the Cognito
    // AdminGetUser API. If the user does not exist in the Cognito User Pool, the booking
    // is rejected, ensuring only authenticated, managed identities can create bookings.
    //
    // Required environment variables / properties:
    //   COGNITO_USER_POOL_ID  – the Cognito User Pool ID (e.g. us-east-1_AbCdEfGhI)
    //   AWS_REGION            – AWS region where the User Pool is hosted
    //
    // IAM permission required on the execution role:
    //   cognito-idp:AdminGetUser on the target User Pool ARN
    private boolean validateGuestWithCognito(String username) {
        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            // If no User Pool is configured (e.g. local dev), skip Cognito validation
            // and allow the booking to proceed. Log a warning so operators are aware.
            logger.warning("COGNITO_USER_POOL_ID is not configured — skipping Cognito "
                    + "identity validation for guest: " + username
                    + ". Set COGNITO_USER_POOL_ID in production deployments.");
            return true;
        }
        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            AdminGetUserRequest adminGetUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse userResponse = cognitoClient.adminGetUser(adminGetUserRequest);

            // Confirm the user account is in an ENABLED / CONFIRMED state
            String userStatus = userResponse.userStatusAsString();
            boolean isConfirmed = "CONFIRMED".equalsIgnoreCase(userStatus);

            if (isConfirmed) {
                logger.info("Cognito identity validation succeeded for guest: " + username
                        + " (status: " + userStatus + ")");
            } else {
                logger.warning("Cognito identity validation: guest " + username
                        + " exists but is not CONFIRMED (status: " + userStatus + ")");
            }
            return isConfirmed;

        } catch (UserNotFoundException e) {
            logger.warning("Cognito identity validation failed — guest not found in User Pool: "
                    + username);
            return false;
        } catch (Exception e) {
            logger.severe("Cognito identity validation error for guest " + username
                    + ": " + e.getMessage());
            // Fail-closed: reject the booking if Cognito is unreachable
            return false;
        }
    }

    // cr-java-0090 FIX: Line 108 — createBooking now validates the guest identity via
    // Amazon Cognito before processing the booking. Authentication credentials and user
    // data are managed exclusively in the Cognito User Pool rather than in local files
    // or in-memory stores, satisfying the cloud-native identity management requirement.
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {

        // cr-java-0090: Validate guest identity against Amazon Cognito User Pool.
        // Replaces file-based user lookup / local credential storage with cloud-native
        // identity management. Booking is rejected if the guest is not a confirmed
        // Cognito user, preventing unauthenticated or unmanaged identities from
        // creating reservations.
        if (!validateGuestWithCognito(guestName)) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Guest identity could not be verified via "
                    + "Amazon Cognito. Ensure the guest account exists and is confirmed "
                    + "in the Cognito User Pool before creating a booking.");
            errorResponse.put("guestName", guestName);
            logger.warning("Booking rejected — Cognito identity validation failed for guest: "
                    + guestName);
            return errorResponse;
        }

        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", DB_HOST);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity.
    // This method has 9+ decision branches. Automated transformation tools flag methods
    // above complexity threshold as high maintenance risk and transformation blockers.
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = 0;
        if (roomType.equals("STANDARD")) { basePrice = 120.0; }
        else if (roomType.equals("DELUXE")) { basePrice = 200.0; }
        else if (roomType.equals("SUITE")) { basePrice = 350.0; }
        else if (roomType.equals("VILLA")) { basePrice = 600.0; }
        else { basePrice = 120.0; }
        if (season.equals("PEAK")) { basePrice = basePrice * 1.5; }
        else if (season.equals("OFF")) { basePrice = basePrice * 0.8; }
        if (loyalty.equals("GOLD")) { basePrice = basePrice * 0.9; }
        else if (loyalty.equals("PLATINUM")) { basePrice = basePrice * 0.8; }
        else if (loyalty.equals("DIAMOND")) { basePrice = basePrice * 0.7; }
        if (nights >= 7) { basePrice = basePrice * 0.95; }
        else if (nights >= 14) { basePrice = basePrice * 0.90; }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        // Same room type validation is repeated here and in calculateRoomPrice.
        // Should be extracted to a shared RoomType enum or validator.
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    private String md5Hash(String input) { // sec-weak-hash-001
        try {
            MessageDigest md = MessageDigest.getInstance("MD5"); // sec-weak-hash-001
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
