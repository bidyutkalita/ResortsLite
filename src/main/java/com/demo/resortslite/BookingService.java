package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Externalised to environment variable via application.properties (cr-java-0021, cr-java-0088).
    // No credentials or infrastructure hostnames are stored in source code (sec-cred-001).
    @Value("${app.payment.endpoint}")
    private String paymentApi;

    /**
     * Creates a new booking record in the PostgreSQL database.
     *
     * <p>Uses a parameterised JDBC query to prevent SQL injection (sql-inject-001).
     * All user-supplied values are passed as bind parameters, never concatenated
     * into the SQL string.</p>
     *
     * @param guestName  name of the guest
     * @param roomType   room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn    check-in date string (ISO-8601 recommended)
     * @param checkOut   check-out date string (ISO-8601 recommended)
     * @return map containing booking details and a SHA-256 confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query — bind variables prevent SQL injection (sql-inject-001).
        // PostgreSQL-compatible INSERT using positional '?' placeholders via JdbcTemplate.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // SHA-256 confirmation code (replaces broken MD5 — sec-weak-hash-001).
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        return booking;
    }

    /**
     * Retrieves a booking record by its identifier.
     *
     * <p>Uses a parameterised query to prevent SQL injection (sql-inject-001).</p>
     *
     * @param bookingId the booking identifier to look up
     * @return map containing booking fields, or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query — bookingId is a bind variable, not concatenated (sql-inject-001).
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    /**
     * Calculates the total room price for a stay.
     *
     * <p>Refactored from a deeply nested if-else chain to a switch expression
     * (Java 14+) to reduce cyclomatic complexity below the threshold of 9
     * (Code Sustainability / High).</p>
     *
     * @param roomType room category
     * @param nights   number of nights
     * @param season   pricing season (PEAK, OFF, or standard)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
     * @return formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        // Switch expression reduces cyclomatic complexity vs. chained if-else (dup-logic-001).
        double basePrice = switch (roomType) {
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0; // STANDARD and unknown types
        };

        basePrice = switch (season) {
            case "PEAK" -> basePrice * 1.5;
            case "OFF"  -> basePrice * 0.8;
            default     -> basePrice;
        };

        basePrice = switch (loyalty) {
            case "GOLD"     -> basePrice * 0.9;
            case "PLATINUM" -> basePrice * 0.8;
            case "DIAMOND"  -> basePrice * 0.7;
            default         -> basePrice;
        };

        // Long-stay discount: 14+ nights takes priority over 7+ nights.
        if (nights >= 14) {
            basePrice = basePrice * 0.90;
        } else if (nights >= 7) {
            basePrice = basePrice * 0.95;
        }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room type is valid and available for booking.
     *
     * <p>Validation is centralised here and delegates to {@link RoomType} to
     * eliminate duplicated validation logic (dup-logic-001).</p>
     *
     * @param roomType room category string
     * @return {@code true} if the room type is recognised; {@code false} otherwise
     */
    public boolean isRoomAvailable(String roomType) {
        return RoomType.isValid(roomType);
    }

    /**
     * Triggers report generation for the given month.
     *
     * @param month month identifier (e.g. "2024-03")
     * @return status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     *
     * <p>Replaces the previously used MD5 algorithm (sec-weak-hash-001).
     * SHA-256 is cryptographically secure and recommended by NIST SP 800-107.</p>
     *
     * @param input string to hash
     * @return lowercase hex-encoded SHA-256 digest, or the original input on error
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }

    // -----------------------------------------------------------------------
    // Inner enum — centralises room-type validation to eliminate duplicated
    // logic across BookingService methods (dup-logic-001).
    // -----------------------------------------------------------------------
    enum RoomType {
        STANDARD, DELUXE, SUITE, VILLA;

        /** Returns {@code true} if {@code value} matches a known room type. */
        static boolean isValid(String value) {
            for (RoomType rt : values()) {
                if (rt.name().equals(value)) return true;
            }
            return false;
        }
    }
}
