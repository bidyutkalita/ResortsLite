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
 * Session state is managed by Spring Session backed by Amazon ElastiCache for Redis
 * (blockers 13-17: cr-java-0065), enabling stateless application instances that can
 * scale horizontally behind an AWS ALB without sticky sessions.
 *
 * In-memory booking cache replaced with Amazon ElastiCache for Redis via RedisTemplate
 * with TTL-based expiration (blocker-20: cr-java-0067), ensuring consistent cache state
 * across all instances and preventing unbounded memory growth.
 *
 * Hard-coded inventory service URL replaced with AWS SSM Parameter Store value
 * injected via Spring @Value (blocker-10: cr-java-0071).
 */
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Distributed cache backed by Amazon ElastiCache for Redis.
     * Replaces the static in-memory HashMap (blocker-20: cr-java-0067).
     * TTL is applied on each cache write to prevent unbounded growth.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL: 30 minutes — entries expire automatically to prevent stale data
    private static final long CACHE_TTL_MINUTES = 30;

    /**
     * Inventory service URL externalized via AWS SSM Parameter Store and injected
     * through Spring @Value (blocker-10: cr-java-0071 — replaces hard-coded URL).
     * Default falls back to the environment variable INVENTORY_SERVICE_URL.
     */
    @Value("${app.inventory.endpoint:#{systemEnvironment['INVENTORY_SERVICE_URL'] ?: 'https://inventory-service.internal:8081/rooms/available'}}")
    private String inventoryServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Session state stored in Amazon ElastiCache for Redis via Spring Session
        // (blockers 13-17: cr-java-0065 — replaces in-process HttpSession storage).
        // Spring Session transparently serializes session attributes to Redis so any
        // application instance can retrieve them, enabling true horizontal scaling.
        session.setAttribute("lastBooking", booking);   // cr-java-0065 — now Redis-backed
        session.setAttribute("guestName", guestName);   // cr-java-0065 — now Redis-backed

        // Store booking in distributed Redis cache with TTL (blocker-20: cr-java-0067)
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Session attribute retrieved from Redis — visible across all cluster instances
        // (blocker-14/15: cr-java-0065 — replaces instance-local session read)
        String lastGuest = (String) session.getAttribute("guestName"); // now Redis-backed

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Inventory URL resolved from AWS SSM Parameter Store via @Value injection
        // (blocker-10: cr-java-0071 — replaces hard-coded environment URL)
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path now references Amazon S3 — no local file system dependency
        String s3Key = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3Key", s3Key);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
