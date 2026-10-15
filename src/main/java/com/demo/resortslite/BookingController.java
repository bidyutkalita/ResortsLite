package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native REST controller.
 *
 * Cloud-readiness fixes applied:
 *  - cr-java-0065: Replaced HttpSession with Amazon ElastiCache for Redis via Spring Session /
 *                  RedisTemplate. Session state is now stored in a distributed Redis cluster,
 *                  enabling stateless application instances and horizontal scaling.
 *  - cr-java-0067: Replaced static in-memory HashMap cache (no TTL) with RedisTemplate-backed
 *                  cache with a configurable TTL, ensuring controlled expiration and consistency
 *                  across all instances.
 *  - cr-java-0071: Replaced hard-coded inventory URL with a value injected from
 *                  application.properties / AWS SSM Parameter Store via @Value.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Distributed Redis cache replacing the former static in-memory HashMap.
     * TTL is applied on every put() call (see createBooking) so entries expire
     * automatically — fixes cr-java-0067 (In-Memory Caching Without TTL).
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** Configurable TTL (minutes) for booking cache entries. Defaults to 60 minutes. */
    @Value("${app.cache.booking.ttl-minutes:60}")
    private long bookingCacheTtlMinutes;

    /**
     * Inventory service URL injected from environment / AWS SSM Parameter Store.
     * Fixes cr-java-0071 (Hard-coded Environment URLs).
     */
    @Value("${app.inventory.endpoint}")
    private String inventoryServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Store session state in Amazon ElastiCache for Redis instead of
        // HttpSession. Keys are namespaced by sessionId so they are visible to every instance
        // behind the AWS ALB. TTL prevents unbounded memory growth.
        if (sessionId != null && !sessionId.isEmpty()) {
            redisTemplate.opsForValue().set(
                    "session:" + sessionId + ":lastBooking", booking,
                    bookingCacheTtlMinutes, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(
                    "session:" + sessionId + ":guestName", guestName,
                    bookingCacheTtlMinutes, TimeUnit.MINUTES);
        }

        // cr-java-0067 FIX: Store booking in Redis with TTL instead of unbounded HashMap.
        redisTemplate.opsForValue().set(
                "booking:" + booking.get("bookingId"), booking,
                bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // cr-java-0065 FIX: Read session state from Redis (ElastiCache) instead of HttpSession.
        // The value is available on every application instance in the cluster.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Object val = redisTemplate.opsForValue().get("session:" + sessionId + ":guestName");
            lastGuest = val != null ? val.toString() : null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: inventoryServiceUrl is injected from application.properties /
        // AWS SSM Parameter Store — no hard-coded URL in source code.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 pre-signed URL generated by BookingService/ReportService.
        // The hard-coded /var/legacy/reports path has been removed.
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
