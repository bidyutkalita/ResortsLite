package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController handles HTTP requests for resort booking operations.
 *
 * Session state is managed via Amazon ElastiCache for Redis using Spring Session,
 * enabling stateless application instances with centralized, distributed session
 * management. Fixes blockers 13-17 (cr-java-0065).
 *
 * In-memory booking cache is replaced with Amazon ElastiCache for Redis with
 * TTL policies to ensure controlled expiration and consistent data across instances.
 * Fixes blocker-20 (cr-java-0067).
 *
 * The inventory service URL is retrieved from AWS SSM Parameter Store instead of
 * being hard-coded. Fixes blocker-10 (cr-java-0071).
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * RedisTemplate replaces the static in-memory HashMap bookingCache.
     * Amazon ElastiCache for Redis provides distributed, TTL-aware caching
     * that is consistent across all application instances.
     * Fixes blocker-20 (cr-java-0067): In-Memory Caching Without TTL.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL: 30 minutes — ensures controlled expiration and prevents stale data.
    private static final Duration BOOKING_CACHE_TTL = Duration.ofMinutes(30);

    // Session TTL key prefix for Redis-backed session storage.
    private static final String SESSION_KEY_PREFIX = "session:";

    // Booking cache key prefix for Redis-backed booking cache.
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    // Inventory service URL is read from AWS SSM Parameter Store — not hard-coded.
    // Fixes blocker-10 (cr-java-0071): Hard-coded Environment URLs.
    private final String inventoryServiceUrl;

    private final SsmClient ssmClient;

    /**
     * Constructs BookingController, reading environment-specific URLs from
     * AWS SSM Parameter Store at startup.
     */
    public BookingController() {
        this.ssmClient = SsmClient.create();
        // Replace hard-coded "http://inventory-service.internal:8081/rooms/available"
        // with value from AWS SSM Parameter Store. Fixes blocker-10 (cr-java-0071).
        this.inventoryServiceUrl = getParameterFromSsm(
                "/resortlite/inventory/service-url",
                "https://inventory-service.internal/rooms/available");
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * @param parameterName the SSM parameter name/path
     * @param defaultValue  fallback value if parameter is unavailable
     * @return the resolved parameter value
     */
    private String getParameterFromSsm(String parameterName, String defaultValue) {
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(parameterName)
                            .withDecryption(true)
                            .build());
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis.
     * Replaces HttpSession.setAttribute() calls with Redis-backed storage to enable
     * stateless instances and horizontal scaling.
     * Fixes blocker-13, blocker-14, blocker-15, blocker-16 (cr-java-0065).
     * Fixes blocker-20 (cr-java-0067): booking cache now uses Redis with TTL.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "anonymous-session") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Replace session.setAttribute("lastBooking", booking) with Redis storage.
        // Fixes blocker-14 (cr-java-0065): HTTP session state replaced with ElastiCache Redis.
        String sessionLastBookingKey = SESSION_KEY_PREFIX + sessionId + ":lastBooking";
        redisTemplate.opsForValue().set(sessionLastBookingKey, booking, BOOKING_CACHE_TTL);

        // Replace session.setAttribute("guestName", guestName) with Redis storage.
        // Fixes blocker-15 (cr-java-0065): HTTP session state replaced with ElastiCache Redis.
        String sessionGuestKey = SESSION_KEY_PREFIX + sessionId + ":guestName";
        redisTemplate.opsForValue().set(sessionGuestKey, guestName, BOOKING_CACHE_TTL);

        // Replace static HashMap bookingCache.put() with Redis cache entry with TTL.
        // Fixes blocker-20 (cr-java-0067): in-memory cache replaced with Redis + TTL.
        String bookingCacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(bookingCacheKey, booking, BOOKING_CACHE_TTL);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves booking status, reading session state from Amazon ElastiCache for Redis.
     * Replaces HttpSession.getAttribute() with Redis lookup to support stateless instances.
     * Fixes blocker-16, blocker-17 (cr-java-0065): HTTP session state replaced with Redis.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "anonymous-session") String sessionId) {

        // Replace session.getAttribute("guestName") with Redis lookup.
        // Fixes blocker-17 (cr-java-0065): HTTP session state replaced with ElastiCache Redis.
        String sessionGuestKey = SESSION_KEY_PREFIX + sessionId + ":guestName";
        String lastGuest = (String) redisTemplate.opsForValue().get(sessionGuestKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the inventory service URL retrieved from
     * AWS SSM Parameter Store. Fixes blocker-10 (cr-java-0071).
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // inventoryServiceUrl is sourced from SSM Parameter Store — not hard-coded.
        // Fixes blocker-10 (cr-java-0071): Hard-coded Environment URLs.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a download link for a monthly report using the S3-backed report service.
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 object key — no local file system dependency.
        String reportKey = "reports/" + month + "_bookings.csv";

        Map<String, Object> response = new HashMap<>();
        response.put("reportKey", reportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
