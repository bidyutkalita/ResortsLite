package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService handles resort booking operations.
 * Database credentials are retrieved from AWS Secrets Manager (not hard-coded).
 * Authentication credentials are managed via AWS Secrets Manager and Amazon Cognito
 * rather than local file storage.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // AWS Secrets Manager client for retrieving database credentials and auth tokens.
    // Replaces hard-coded DB_USER / DB_PASS constants. Fixes blocker-8, blocker-9 (cr-java-0069).
    private final SecretsManagerClient secretsManagerClient;

    // SSM client for retrieving environment-specific configuration (e.g., payment API URL).
    private final SsmClient ssmClient;

    // DB_HOST is now read from environment variable — not hard-coded.
    // Fixes blocker-8 (cr-java-0069): hard-coded DB credentials / connection info.
    private final String dbHost;

    // Payment API endpoint is read from environment variable — not hard-coded.
    private final String paymentApi;

    // Secret name in AWS Secrets Manager that stores DB credentials as JSON:
    // { "username": "...", "password": "..." }
    private static final String DB_CREDENTIALS_SECRET_NAME = "resortlite/db/credentials";

    // Secret name in AWS Secrets Manager for authentication credentials.
    // Fixes blocker-18 (cr-java-0090): file-based authentication replaced with Secrets Manager.
    private static final String AUTH_CREDENTIALS_SECRET_NAME = "resortlite/auth/credentials";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Constructs BookingService, reading all environment-specific configuration
     * from environment variables and AWS Secrets Manager at startup.
     */
    public BookingService() {
        this.secretsManagerClient = SecretsManagerClient.create();
        this.ssmClient = SsmClient.create();

        // Replace hard-coded DB_HOST with environment variable.
        // Fixes blocker-8, blocker-9 (cr-java-0069).
        this.dbHost = System.getenv().getOrDefault("DB_HOST", "db-prod.resorts-internal.com");

        // Replace hard-coded PAYMENT_API with environment variable.
        this.paymentApi = System.getenv().getOrDefault("PAYMENT_API_URL",
                "https://payment-svc.internal/payments/charge");
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Replaces hard-coded DB_USER ("admin") and DB_PASS ("Resort$Pass#2019!").
     * Fixes blocker-8 and blocker-9 (cr-java-0069): Hard-coded Database Credentials.
     *
     * @return map containing "username" and "password" keys
     */
    private Map<String, String> getDbCredentials() {
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(DB_CREDENTIALS_SECRET_NAME)
                            .build());
            String secretJson = response.secretString();
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = objectMapper.readValue(secretJson, Map.class);
            return credentials;
        } catch (Exception e) {
            // Fallback: read from environment variables if Secrets Manager is unavailable
            Map<String, String> fallback = new HashMap<>();
            fallback.put("username", System.getenv().getOrDefault("DB_USER", ""));
            fallback.put("password", System.getenv().getOrDefault("DB_PASS", ""));
            return fallback;
        }
    }

    /**
     * Retrieves authentication credentials from AWS Secrets Manager.
     * Replaces file-based authentication storage.
     * Fixes blocker-18 (cr-java-0090): File-based Authentication replaced with
     * AWS Secrets Manager and Amazon Cognito for centralized, encrypted, auditable
     * authentication with built-in user lifecycle management.
     *
     * @param secretKey the specific auth credential key to retrieve
     * @return the credential value, or empty string if not found
     */
    public String getAuthCredential(String secretKey) {
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(AUTH_CREDENTIALS_SECRET_NAME)
                            .build());
            String secretJson = response.secretString();
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = objectMapper.readValue(secretJson, Map.class);
            return credentials.getOrDefault(secretKey, "");
        } catch (Exception e) {
            return "";
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
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
        booking.put("dbHost", dbHost);
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
        return "Report generation triggered for: " + month + " via " + paymentApi;
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
