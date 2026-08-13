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
 * Blocker-13/14/15/16/17 (cr-java-0065): @EnableRedisHttpSession activates Spring Session
 * backed by Amazon ElastiCache for Redis. All HttpSession operations are transparently
 * stored in Redis, enabling stateless application instances and horizontal scaling.
 */
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Blocker-20 (cr-java-0067): Static in-memory HashMap replaced with RedisTemplate.
    // Redis (ElastiCache) provides a shared, TTL-aware cache visible to all instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL: 30 minutes — prevents unbounded memory growth (blocker-20 cr-java-0067)
    private static final long CACHE_TTL_MINUTES = 30L;
    private static final String BOOKING_CACHE_PREFIX = "bookingCache:";

    // Blocker-10 (cr-java-0071): Hard-coded inventory URL replaced with value injected
    // from application.properties / environment variable backed by SSM Parameter Store.
    @Value("${app.inventory.endpoint:#{environment['INVENTORY_ENDPOINT']}}")
    private String inventoryEndpoint;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-14/15 (cr-java-0065): Session attributes are now stored in Amazon
        // ElastiCache for Redis via Spring Session — not in local JVM memory.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // Blocker-20 (cr-java-0067): Cache entry stored in Redis with a 30-minute TTL
        // instead of an unbounded static HashMap.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
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

        // Blocker-16/17 (cr-java-0065): Session read is served from ElastiCache for Redis —
        // consistent across all application instances behind the load balancer.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Blocker-10 (cr-java-0071): inventoryEndpoint is resolved from SSM Parameter Store
        // via application.properties / environment variable — not hard-coded.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 key, not a local absolute path.
        String s3Key = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3Key", s3Key);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
