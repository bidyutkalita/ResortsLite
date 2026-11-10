package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — resort booking business logic.
 *
 * <p>cr-java-0090 FIX (File-based Authentication, line 108):
 * The previous implementation stored authentication credentials and user data in
 * local static constants and generated authentication/confirmation tokens using the
 * broken MD5 algorithm ({@code md5Hash(bookingId + guestName)} at line 108 of the
 * original source).  This approach does not scale horizontally and creates security
 * and consistency issues in distributed cloud environments.
 *
 * <p>The fix migrates authentication credentials and user data from local file/constant
 * storage to AWS cloud-native services:
 * <ul>
 *   <li><b>AWS Secrets Manager</b> — database credentials (username, password, host)
 *       are fetched at startup via {@link AwsSecretsManagerConfig.DbCredentials}.
 *       No credentials are stored in source code, version control, or container images.</li>
 *   <li><b>Amazon Cognito</b> — guest user identities are registered in a Cognito User
 *       Pool via {@link CognitoAuthService#registerGuestUser(String, String, String)},
 *       providing centralised, encrypted, and auditable authentication with built-in
 *       user lifecycle management.</li>
 *   <li><b>Secure token generation</b> — booking confirmation codes are now generated
 *       by {@link CognitoAuthService#generateSecureConfirmationCode(String, String)}
 *       using {@code UUID.randomUUID()} (backed by {@code SecureRandom}), replacing the
 *       broken MD5 digest that was previously computed at line 108.</li>
 * </ul>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIXED: Hard-coded DB_USER and DB_PASS constants have been removed.
    // Database credentials (username, password, host) are now retrieved at startup from
    // AWS Secrets Manager via the AwsSecretsManagerConfig.DbCredentials bean, ensuring
    // credentials are never stored in source code, version control, or container images.
    // The DB host is exposed here only for informational/diagnostic purposes and is
    // sourced from the same Secrets Manager secret (no hard-coded value).
    @Autowired
    private AwsSecretsManagerConfig.DbCredentials dbCredentials;

    // cr-java-0021: Payment API endpoint externalised to environment variable / properties.
    @Value("${app.payment.endpoint:${PAYMENT_API_ENDPOINT:http://localhost:9090/payments/charge}}")
    private String paymentApi;

    // cr-java-0090 FIX: CognitoAuthService injected to replace file-based authentication.
    // Provides secure confirmation code generation (replaces MD5 at line 108) and
    // Cognito User Pool registration for guest user identity management.
    @Autowired
    private CognitoAuthService cognitoAuthService;

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

        // cr-java-0090 FIX (line 108): Replaced broken MD5-based confirmation code generation
        //   String confirmCode = md5Hash(bookingId + guestName);
        // with a call to CognitoAuthService.generateSecureConfirmationCode(), which uses
        // UUID.randomUUID() (SecureRandom-backed) to produce a cryptographically secure,
        // unpredictable token.  The token is not derived from user-supplied data, eliminating
        // hash-based enumeration attacks.  User identity is now managed in Amazon Cognito
        // rather than in local files or static constants.
        String confirmCode = cognitoAuthService.generateSecureConfirmationCode(bookingId, guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // DB host is now sourced from AWS Secrets Manager — no hard-coded value in code.
        booking.put("dbHost", dbCredentials.getHost());
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

    // cr-java-0090 FIX: The md5Hash() method (original line 108) has been removed.
    // MD5 is a broken hash algorithm (RFC 6151) and must not be used for any
    // security-related hashing.  Confirmation code generation has been migrated to
    // CognitoAuthService.generateSecureConfirmationCode(), which uses SecureRandom-backed
    // UUID generation.  User authentication is now handled by Amazon Cognito User Pools
    // via CognitoAuthService, replacing all file-based credential and user data storage.
}
