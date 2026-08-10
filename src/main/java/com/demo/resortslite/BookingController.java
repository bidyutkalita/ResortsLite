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
 * Blocker-13,14,15,16,17: HttpSession replaced with Azure Cache for Redis via
 * Spring Session + RedisTemplate, enabling stateless horizontal scaling.
 *
 * Blocker-20: In-memory bookingCache (HashMap without TTL) replaced with
 * Azure Cache for Redis with TTL policies to prevent memory exhaustion and
 * ensure cache consistency across instances.
 *
 * Blocker-10: Hard-coded inventory URL replaced with value injected from
 * Azure App Configuration / environment variable.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Blocker-20: In-memory HashMap cache replaced with Azure Cache for Redis.
    // RedisTemplate provides distributed caching with TTL support across all instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL in minutes — configurable via environment variable
    @Value("${BOOKING_CACHE_TTL_MINUTES:60}")
    private long bookingCacheTtlMinutes;

    // Blocker-10: Hard-coded inventory URL replaced with Azure App Configuration value.
    @Value("${INVENTORY_SERVICE_URL:${app.inventory.endpoint:https://inventory-service.internal/rooms/available}}")
    private String inventoryServiceUrl;

    private static final String SESSION_PREFIX = "session:";
    private static final String CACHE_PREFIX   = "booking:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-13,14: Session state stored in Azure Cache for Redis instead of HttpSession.
        // This enables stateless architecture and horizontal scaling across multiple instances.
        if (sessionId != null && !sessionId.isEmpty()) {
            redisTemplate.opsForHash().put(SESSION_PREFIX + sessionId, "lastBooking", booking);
            redisTemplate.opsForHash().put(SESSION_PREFIX + sessionId, "guestName", guestName);
            redisTemplate.expire(SESSION_PREFIX + sessionId, bookingCacheTtlMinutes, TimeUnit.MINUTES);
        }

        // Blocker-20: Booking cached in Redis with TTL — replaces in-memory HashMap without TTL.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set(CACHE_PREFIX + bookingId, booking,
                bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        // Blocker-15: Session state read from Azure Cache for Redis instead of HttpSession.
        // Returns consistent data regardless of which instance handles the request.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Object val = redisTemplate.opsForHash().get(SESSION_PREFIX + sessionId, "guestName");
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
        // Blocker-10: inventoryServiceUrl is injected from Azure App Configuration /
        // environment variable — no hard-coded URL in source code.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now a Blob Storage reference, not a local file path
        String blobReference = "resort-reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("blobReference", blobReference);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    // Blocker-16,17: Additional session operations now use Redis.
    @DeleteMapping("/session")
    public Map<String, Object> clearSession(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Map<String, Object> response = new HashMap<>();
        if (sessionId != null && !sessionId.isEmpty()) {
            redisTemplate.delete(SESSION_PREFIX + sessionId);
            response.put("status", "session cleared");
        } else {
            response.put("status", "no session id provided");
        }
        return response;
    }
}
