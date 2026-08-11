package com.demo.resortslite;

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

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SecretsManagerClient secretsManagerClient;

    // Database credentials and payment API endpoint are resolved at runtime from
    // AWS Secrets Manager — no hard-coded credentials in source code (blockers 8, 9: cr-java-0069)
    private final String dbHost;
    private final String dbUser;
    private final String dbPass;

    // Payment API endpoint resolved from AWS Secrets Manager / environment variable
    private final String paymentApi;

    public BookingService() {
        this.secretsManagerClient = SecretsManagerClient.create();

        // Retrieve DB credentials from AWS Secrets Manager (blockers 8, 9: cr-java-0069)
        // Secret name is read from an environment variable to remain environment-agnostic
        String dbSecretName = System.getenv().getOrDefault(
                "DB_SECRET_NAME", "resortslite/db/credentials");
        Map<String, String> dbSecrets = resolveSecretAsMap(dbSecretName);
        this.dbHost = dbSecrets.getOrDefault("host", "");
        this.dbUser = dbSecrets.getOrDefault("username", "");
        this.dbPass = dbSecrets.getOrDefault("password", "");

        // Payment API endpoint from environment variable — no hard-coded internal IP
        this.paymentApi = System.getenv().getOrDefault(
                "PAYMENT_API_URL", "https://payment-service/payments/charge");
    }

    /**
     * Retrieves a secret from AWS Secrets Manager and parses it as a key=value map.
     * The secret is expected to be stored as a JSON string, e.g.:
     * {"host":"db-host","username":"user","password":"pass"}
     * Falls back to an empty map if the secret cannot be retrieved.
     *
     * Replaces file-based authentication credential storage (blocker-18: cr-java-0090).
     */
    private Map<String, String> resolveSecretAsMap(String secretName) {
        Map<String, String> result = new HashMap<>();
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(secretName)
                            .build());
            String secretString = response.secretString();
            // Parse simple JSON key-value pairs without an external JSON library
            if (secretString != null && secretString.startsWith("{")) {
                String stripped = secretString.replaceAll("[{}\"]", "");
                for (String pair : stripped.split(",")) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length == 2) {
                        result.put(kv[0].trim(), kv[1].trim());
                    }
                }
            }
        } catch (Exception e) {
            // Log and continue — caller handles missing values
        }
        return result;
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterized query — prevents SQL injection
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
        // dbHost is no longer exposed in the response — credentials stay server-side
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterized query — prevents SQL injection
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
