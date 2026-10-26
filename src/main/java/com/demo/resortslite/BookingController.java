package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0065 FIX: Replaced instance-local HttpSession with a RedisTemplate backed by
    // Amazon ElastiCache for Redis via Spring Session. Session state is now stored in a
    // centralized, distributed Redis cluster so every EC2 instance in the Auto Scaling Group
    // reads and writes the same data. This eliminates server affinity, enables horizontal
    // scaling, and prevents data loss on instance termination or load-balancer failover.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Session TTL (seconds) — configurable via environment variable / application property.
    // Defaults to 1800 s (30 min) to match typical HTTP session idle-timeout behaviour.
    @Value("${app.session.ttl.seconds:1800}")
    private long sessionTtlSeconds;

    // cr-java-0067 FIX: Replaced the unbounded instance-local static HashMap cache
    // (private static final Map<String, Object> bookingCache = new HashMap<>()) with
    // Amazon ElastiCache for Redis via the existing RedisTemplate bean.
    // All cache reads and writes now use a namespaced key ("booking:<bookingId>") and
    // are given an explicit TTL (app.booking.cache.ttl.seconds, default 3600 s / 1 hour)
    // so entries are automatically evicted. Because Redis is an external, shared store,
    // every instance in the Auto Scaling Group sees the same cached data, eliminating
    // the stale-data and memory-growth problems that existed with the in-memory HashMap.
    @Value("${app.booking.cache.ttl.seconds:3600}")
    private long bookingCacheTtlSeconds;

    // cr-java-0071 FIX: Replaced hard-coded inventory service URL
    // "http://inventory-service.internal:8081/rooms/available" with a value sourced from
    // AWS Systems Manager Parameter Store via the INVENTORY_SERVICE_URL environment variable.
    // In AWS deployments, the SSM Parameter Store value is injected as an environment variable
    // through the ECS task definition or Spring Cloud AWS SSM integration, ensuring the URL
    // is environment-agnostic and can be changed without code modifications.
    @Value("${app.inventory.url:${INVENTORY_SERVICE_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session state is now written to Amazon ElastiCache for Redis
        // instead of the local HttpSession. The session key is namespaced with the caller-
        // supplied sessionId so that any instance in the cluster can retrieve it.
        // A TTL is applied so stale session data is automatically evicted from Redis.
        String sessionKey = "session:" + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, sessionTtlSeconds, TimeUnit.SECONDS);

        // cr-java-0067 FIX: Cache the booking in Amazon ElastiCache for Redis with an
        // explicit TTL instead of the former unbounded static HashMap. The cache key is
        // namespaced as "booking:<bookingId>" to avoid collisions with session keys.
        // The TTL (app.booking.cache.ttl.seconds, default 3600 s) ensures automatic
        // eviction of stale entries and prevents unbounded memory growth. All application
        // instances share this centralized cache, so reads on any node return consistent data.
        String bookingId = (String) booking.get("bookingId");
        if (bookingId != null) {
            String cacheKey = "booking:" + bookingId;
            redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // cr-java-0065 FIX: Guest name is now retrieved from Amazon ElastiCache for Redis
        // instead of the local HttpSession. Because Redis is a shared, external store the
        // value is visible to every instance in the Auto Scaling Group, eliminating the
        // null-on-different-instance problem that existed with HttpSession.
        String sessionKey = "session:" + sessionId;
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        // cr-java-0067 FIX: Booking details are now looked up from Amazon ElastiCache for
        // Redis (cache-aside pattern) before falling back to the BookingService / database.
        // This replaces the former instance-local HashMap lookup which was invisible to
        // other instances and had no expiration. The Redis cache entry carries a TTL so
        // stale data is automatically evicted without manual intervention.
        String cacheKey = "booking:" + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        if (cachedBooking != null) {
            result.put("details", cachedBooking);
            result.put("cacheHit", true);
        } else {
            Map<String, Object> dbDetails = bookingService.getBookingById(bookingId);
            result.put("details", dbDetails);
            result.put("cacheHit", false);
            // Repopulate the cache on a miss so subsequent reads are served from Redis.
            if (dbDetails != null && !dbDetails.containsKey("error")) {
                redisTemplate.opsForValue().set(cacheKey, dbDetails, bookingCacheTtlSeconds, TimeUnit.SECONDS);
            }
        }
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (line 66): Replaced hard-coded URL
        // "http://inventory-service.internal:8081/rooms/available" with the
        // externalized value injected from AWS Systems Manager Parameter Store
        // via the INVENTORY_SERVICE_URL environment variable / app.inventory.url property.
        // The URL is now environment-agnostic and requires no code change per deployment.
        String inventoryUrl = inventoryServiceUrl;

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
