package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native booking operations.
 *
 * <p>Cloud readiness changes applied:
 * <ul>
 *   <li><b>cr-java-0069 (blockers 8 &amp; 9)</b>: Hard-coded {@code DB_USER} / {@code DB_PASS}
 *       constants removed. Database credentials are now retrieved at runtime from
 *       <b>AWS Secrets Manager</b> via {@link #getDbCredentials()}. The secret name is
 *       injected through the environment variable {@code DB_SECRET_NAME} (set by ECS task
 *       definition / Elastic Beanstalk environment configuration).</li>
 *   <li><b>cr-java-0090 (blocker 18)</b>: File-based authentication credential storage
 *       replaced with <b>AWS Secrets Manager</b> for credential retrieval and the pattern
 *       is aligned with Amazon Cognito for user identity management. No credentials are
 *       stored in local files or hard-coded in source.</li>
 *   <li>Hard-coded {@code PAYMENT_API} hostname externalised to environment variable
 *       {@code PAYMENT_API_URL} / application property {@code app.payment.endpoint}.</li>
 * </ul>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------
    // Cloud-native configuration — injected from environment variables set by
    // ECS task definition, Elastic Beanstalk, or application.properties which
    // itself reads from SSM Parameter Store via Spring Cloud AWS (if configured).
    // -----------------------------------------------------------------------

    /**
     * AWS Secrets Manager secret name that holds the database credentials JSON.
     * Expected secret format: {"username":"...","password":"...","host":"..."}
     * Injected from environment variable DB_SECRET_NAME.
     */
    @Value("${cloud.secrets.db-secret-name:${DB_SECRET_NAME:resortsLite/db/credentials}}")
    private String dbSecretName;

    /**
     * Payment API endpoint — injected from environment variable PAYMENT_API_URL.
     * Replaces the former hard-coded {@code http://10.0.1.45:9090/payments/charge}.
     */
    @Value("${app.payment.endpoint:${PAYMENT_API_URL:https://payment-svc.internal/payments/charge}}")
    private String paymentApiUrl;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = new ObjectMapper();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // DB host is no longer exposed in the response — credentials are managed by Secrets Manager
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

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
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApiUrl;
    }

    // -----------------------------------------------------------------------
    // AWS Secrets Manager integration
    // -----------------------------------------------------------------------

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     *
     * <p>The secret is expected to be a JSON string with the following structure:
     * <pre>
     * {
     *   "username": "admin",
     *   "password": "...",
     *   "host": "db-prod.resorts-internal.com",
     *   "port": "5432",
     *   "dbname": "resortdb"
     * }
     * </pre>
     *
     * <p>This replaces the former hard-coded {@code DB_USER} and {@code DB_PASS} constants
     * (cr-java-0069) and the file-based credential storage pattern (cr-java-0090).
     * Credentials are never stored in source code, property files, or local files.
     *
     * @return map containing database connection properties
     */
    public Map<String, String> getDbCredentials() {
        Map<String, String> credentials = new HashMap<>();
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretJson = response.secretString();

            JsonNode secretNode = objectMapper.readTree(secretJson);
            credentials.put("username", secretNode.path("username").asText());
            credentials.put("password", secretNode.path("password").asText());
            credentials.put("host", secretNode.path("host").asText());
            credentials.put("port", secretNode.path("port").asText("5432"));
            credentials.put("dbname", secretNode.path("dbname").asText("resortdb"));
        } catch (Exception e) {
            // Log error — do NOT fall back to hard-coded credentials
            throw new RuntimeException(
                    "Failed to retrieve database credentials from AWS Secrets Manager "
                            + "(secret: " + dbSecretName + "): " + e.getMessage(), e);
        }
        return credentials;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

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
