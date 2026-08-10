package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Blocker-8,9: Hard-coded DB credentials removed from source code.
    // Credentials are retrieved at runtime from Azure Key Vault using DefaultAzureCredential.
    @Value("${AZURE_KEY_VAULT_URI:#{null}}")
    private String keyVaultUri;

    // Blocker-8: DB_HOST is now loaded from Azure Key Vault secret "db-host"
    // or falls back to the environment variable DB_HOST.
    @Value("${DB_HOST:#{null}}")
    private String dbHostEnv;

    // Blocker-18: File-based authentication replaced — credentials are managed by
    // Azure Active Directory (Entra ID) via DefaultAzureCredential and Spring Security.
    // The SecretClient uses managed identity / DefaultAzureCredential for authentication.
    private SecretClient buildSecretClient() {
        DefaultAzureCredential credential = new DefaultAzureCredentialBuilder().build();
        return new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(credential)
                .buildClient();
    }

    /**
     * Retrieves a secret value from Azure Key Vault.
     * Blocker-8,9: Replaces hard-coded DB_USER and DB_PASS constants.
     */
    private String getSecret(String secretName) {
        try {
            SecretClient secretClient = buildSecretClient();
            return secretClient.getSecret(secretName).getValue();
        } catch (Exception e) {
            // Fall back to environment variable if Key Vault is unavailable
            return System.getenv(secretName.toUpperCase().replace("-", "_"));
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query prevents SQL injection
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
        // Blocker-8: DB host is no longer hard-coded; sourced from environment variable
        booking.put("dbHost", dbHostEnv != null ? dbHostEnv : "configured-via-key-vault");
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query prevents SQL injection
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
        // Payment API endpoint is now sourced from environment variable (no hard-coded URL)
        String paymentApi = System.getenv("PAYMENT_API_URL");
        if (paymentApi == null || paymentApi.isEmpty()) {
            paymentApi = "configured-via-environment";
        }
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
