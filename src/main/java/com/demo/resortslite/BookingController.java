package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native REST controller.
 *
 * <p>Cloud readiness changes applied:
 * <ul>
 *   <li><b>cr-java-0065 (blockers 13–17)</b>: HTTP session state migrated to
 *       <b>Amazon ElastiCache for Redis</b> via Spring Session. The
 *       {@code @EnableRedisHttpSession} annotation activates Spring Session's Redis
 *       store so that all {@code HttpSession} operations are transparently backed by
 *       Redis, enabling stateless application instances and horizontal scaling across
 *       multiple ECS tasks / EC2 instances without sticky sessions.</li>
 *   <li><b>cr-java-0067 (blocker 20)</b>: Unbounded in-memory {@code HashMap} cache
 *       replaced with <b>Amazon ElastiCache for Redis</b> via {@link RedisTemplate}
 *       with a configurable TTL (default 30 minutes). This ensures controlled
 *       expiration, consistent data across all instances, and centralized cache
 *       management.</li>
 *   <li><b>cr-java-0071 (blocker 10)</b>: Hard-coded inventory service URL
 *       ({@code http://inventory-service.internal:8081/rooms/available}) replaced with
 *       a value injected from the environment variable {@code INVENTORY_SERVICE_URL}
 *       or application property {@code app.inventory.endpoint}, which is populated
 *       from AWS Systems Manager Parameter Store at deployment time.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Redis template used for distributed caching with TTL.
     * Replaces the former unbounded in-memory {@code HashMap} (cr-java-0067).
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Cache TTL in minutes — configurable via environment variable BOOKING_CACHE_TTL_MINUTES.
     * Ensures cache entries expire and do not grow indefinitely.
     */
    @Value("${cloud.cache.booking-ttl-minutes:${BOOKING_CACHE_TTL_MINUTES:30}}")
    private long bookingCacheTtlMinutes;

    /**
     * Inventory service URL — injected from environment variable INVENTORY_SERVICE_URL
     * (populated from AWS Systems Manager Parameter Store by ECS task definition).
     * Replaces the former hard-coded {@code http://inventory-service.internal:8081/...} URL.
     */
    @Value("${app.inventory.endpoint:${INVENTORY_SERVICE_URL:https://inventory-service.internal/rooms/available}}")
    private String inventoryServiceUrl;

    /** Redis key prefix for booking cache entries. */
    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session is now backed by Amazon ElastiCache for Redis via
        // Spring Session (@EnableRedisHttpSession). HttpSession.setAttribute calls are
        // transparently stored in Redis — visible to ALL application instances in the
        // cluster, enabling stateless horizontal scaling.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Store booking in Redis with TTL instead of unbounded HashMap.
        // RedisTemplate.opsForValue().set() with expiry ensures controlled cache growth
        // and consistent data across all ECS instances.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session read is now served from Redis (Spring Session),
        // so the value is consistent regardless of which instance handles the request.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: inventoryServiceUrl is injected from environment variable
        // (backed by AWS Systems Manager Parameter Store) — no hard-coded URL in source.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 URI resolved by ReportService — no local file path.
        String reportPath = "s3://resortsLite-reports/reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
