package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.ssm.SsmClient;

// cr-java-0065 FIX: javax.servlet.http.HttpSession is retained as the API contract,
// but Spring Session (spring-session-data-redis) transparently replaces the default
// in-memory session store with Amazon ElastiCache for Redis at runtime.
// No sticky sessions or server affinity are required — all session data is stored
// centrally in Redis, making every application instance fully stateless.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0071 FIX: Injected SSM client to resolve environment-specific URLs at runtime.
    @Autowired
    private SsmClient ssmClient;

    // cr-java-0071 FIX: SSM parameter name for the inventory service URL, injected from
    // application.properties / environment variable so it can differ per environment.
    @Value("${ssm.parameter.inventory.url:/resortslite/inventory/service-url}")
    private String inventoryUrlParamName;

    // cr-java-0071 FIX: Fallback URL used only when SSM is unavailable (e.g. local dev).
    @Value("${app.inventory.url.default:https://inventory-service.internal:8081/rooms/available}")
    private String inventoryUrlDefault;

    // cr-java-0067 FIX: Replaced unbounded in-memory HashMap cache (bookingCache) with
    // Amazon ElastiCache for Redis via Spring Data RedisTemplate.
    // Benefits:
    //  - TTL-controlled expiration (configurable via app.booking.cache.ttl-seconds) prevents
    //    indefinite memory growth and stale data accumulation.
    //  - Centralised cache shared across all EC2 instances — no per-instance cache divergence.
    //  - Cache entries are automatically evicted by Redis after the configured TTL, ensuring
    //    data freshness and bounded memory usage in the ElastiCache cluster.
    //  - Survives application restarts and Auto Scaling scale-in events.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 FIX: Cache TTL in seconds — externalised to application.properties /
    // environment variable so it can be tuned per environment without code changes.
    // Default: 3600 seconds (1 hour). Override via APP_BOOKING_CACHE_TTL_SECONDS env var.
    @Value("${app.booking.cache.ttl-seconds:${APP_BOOKING_CACHE_TTL_SECONDS:3600}}")
    private long bookingCacheTtlSeconds;

    // cr-java-0067 FIX: Redis key prefix for booking cache entries — isolates ResortsLite
    // booking cache from other applications sharing the same ElastiCache cluster.
    private static final String BOOKING_CACHE_KEY_PREFIX = "resortslite:booking:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cr-java-0065 FIX: HttpSession is now backed by Amazon ElastiCache for Redis
            // via Spring Session (RedisHttpSessionConfiguration). The session is stored
            // centrally — any EC2 instance in the Auto Scaling group can serve subsequent
            // requests without sticky sessions or data loss on scale-in / failover.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now persisted to Amazon ElastiCache for
        // Redis by Spring Session. The setAttribute API is unchanged; Spring Session
        // intercepts the call and serialises the value to the shared Redis store, making
        // the data available to all application instances behind the AWS ALB.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Store booking in Amazon ElastiCache for Redis with TTL.
        // The opsForValue().set() call with TimeUnit.SECONDS ensures the cache entry
        // expires automatically after bookingCacheTtlSeconds, preventing unbounded memory
        // growth and stale data. All instances share the same Redis cache — no divergence.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cr-java-0065 FIX: HttpSession is now backed by Amazon ElastiCache for Redis
            // via Spring Session. Reading session attributes works correctly across all
            // instances — the session ID cookie routes the lookup to the shared Redis store.
            HttpSession session) {

        // cr-java-0065 FIX: getAttribute now reads from the centralised Redis session store
        // managed by Spring Session + ElastiCache. The value is consistent regardless of
        // which EC2 instance handles the request, eliminating the previous server-affinity
        // requirement and enabling true horizontal scaling.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Replaced hard-coded environment-specific URL
        //   "http://inventory-service.internal:8081/rooms/available"
        // with a value retrieved at runtime from AWS Systems Manager Parameter Store.
        // The SSM parameter name (/resortslite/inventory/service-url) is itself
        // externalised via application.properties / environment variable, so the
        // same binary can be deployed to dev, staging, and production without any
        // code or build changes — only the SSM parameter value differs per environment.
        String inventoryUrl = AwsSsmConfig.getParameter(
                ssmClient,
                inventoryUrlParamName,
                inventoryUrlDefault);

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image. Container images
        // have their own isolated file systems — /var/legacy/reports won't be present.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
