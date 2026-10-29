package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069 / cz-java-0063: HttpSession is retained as the interface; Spring Session
// (spring-session-data-redis) transparently proxies all HttpSession operations to Amazon
// ElastiCache (Redis) at runtime, enabling session sharing across all EKS pod replicas
// and surviving container restarts — session state is no longer in-memory / per-container.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0070: Local in-memory HashMap cache replaced with Amazon ElastiCache (Redis)
    // via RedisTemplate. Cache entries are stored externally and shared across all EKS pod
    // replicas, enabling correct horizontal scaling. Connection details (SPRING_REDIS_HOST,
    // SPRING_REDIS_PORT) are injected via Kubernetes ConfigMap/Secret with IRSA-secured access.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache key prefix to namespace booking entries in Redis
    private static final String BOOKING_CACHE_PREFIX = "bookingCache:";

    // cz-java-0070: Cache TTL — entries expire after 30 minutes to avoid stale data
    private static final long BOOKING_CACHE_TTL_MINUTES = 30L;

    // cz-java-0057: Absolute file path replaced with environment variable injected via @Value
    @Value("${app.report.base.path:/var/reports/}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069: HttpSession is now backed by Amazon ElastiCache (Redis) via
            // Spring Session (RedisSessionConfig + @EnableRedisHttpSession). Session data is
            // stored externally in Redis and shared across all EKS pod instances — no longer
            // tied to a single container's in-memory store.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 [Fix]: session.setAttribute now writes to Amazon ElastiCache (Redis)
        // via Spring Session proxy — session state is durable across container restarts and
        // visible to all EKS pod replicas. SPRING_REDIS_HOST / SPRING_REDIS_PORT are injected
        // as environment variables (Kubernetes ConfigMap/Secret) for EKS deployment.
        session.setAttribute("lastBooking", booking); // cz-java-0069: Redis-backed via Spring Session
        session.setAttribute("guestName", guestName); // cz-java-0069: Redis-backed via Spring Session

        // cz-java-0070: Store booking in Amazon ElastiCache (Redis) instead of local HashMap.
        // RedisTemplate writes to the shared Redis cluster, making the cached entry visible
        // to all pod replicas. TTL prevents unbounded memory growth in the cache.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0069: HttpSession is now backed by Amazon ElastiCache (Redis) via
            // Spring Session (RedisSessionConfig). Reads are served from the shared Redis
            // store, so any EKS pod replica returns consistent session data.
            HttpSession session) {

        // cz-java-0069: session.getAttribute now reads from Redis via Spring Session proxy —
        // returns correct value regardless of which pod replica handles the request.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // internal inventory service. AWS ALB, WAF, and Well-Architected security review
        // enforce HTTPS. This call will be blocked or flagged in a cloud-native setup.
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available"; // cr-java-0088

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // cz-java-0057: Replaced hardcoded absolute path "/var/legacy/reports/" with
        // environment variable ${APP_REPORT_BASE_PATH} injected via @Value into reportBasePath
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
