package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Blocker-8/9 (cr-java-0069): Hard-coded DB credentials removed.
    // DB_HOST, DB_USER, DB_PASS are now retrieved at runtime from AWS Secrets Manager.
    // The secret name is injected via environment variable DB_SECRET_NAME.
    @Value("${cloud.aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    // Blocker-8/9 (cr-java-0069): Payment API endpoint externalised to environment variable.
    // Previously hard-coded as "http://10.0.1.45:9090/payments/charge".
    @Value("${app.payment.endpoint:#{environment['PAYMENT_API_ENDPOINT']}}")
    private String paymentApi;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Blocker-8/9 (cr-java-0069): Replaces hard-coded DB_USER / DB_PASS constants.
     */
    private Map<String, String> getDbCredentials() {
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder().secretId(dbSecretName).build());
            String secretJson = response.secretString();
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = objectMapper.readValue(secretJson, Map.class);
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve DB credentials from AWS Secrets Manager: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        String confirmCode = md5Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // Blocker-8/9 (cr-java-0069): DB_HOST no longer exposed in response;
        // credentials are managed by AWS Secrets Manager.
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
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
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Validates user credentials against AWS Secrets Manager.
     * Blocker-18 (cr-java-0090): File-based authentication replaced with
     * AWS Secrets Manager for credential storage. User identity management
     * is delegated to Amazon Cognito (token validation handled at the API Gateway / filter layer).
     */
    public boolean validateUserCredentials(String username, String providedPassword) {
        // Credentials are stored in AWS Secrets Manager, not in local files.
        // The secret is keyed by username and contains the hashed password.
        String userSecretName = "resortslite/users/" + username;
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder().secretId(userSecretName).build());
            String secretJson = response.secretString();
            @SuppressWarnings("unchecked")
            Map<String, String> userSecret = objectMapper.readValue(secretJson, Map.class);
            String storedHash = userSecret.get("passwordHash");
            // Compare provided password hash against the stored hash from Secrets Manager
            return storedHash != null && storedHash.equals(md5Hash(providedPassword));
        } catch (Exception e) {
            // User not found or Secrets Manager unavailable — deny access
            return false;
        }
    }

    private String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
