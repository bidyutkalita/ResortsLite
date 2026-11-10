package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069: Replaced in-memory HttpSession with Spring Session backed by Amazon ElastiCache
// for Redis on EKS. Spring Session transparently intercepts all HttpSession calls and stores
// session data in Redis, enabling stateless horizontal scaling across multiple EKS pod replicas.
// IRSA (IAM Roles for Service Accounts) is used for secure, credential-free ElastiCache access.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0057: Replaced hardcoded absolute file path with environment variable
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // cz-java-0070: Replaced local in-memory HashMap cache with Amazon ElastiCache (Redis)
    // via RedisTemplate. The local HashMap was instance-local and invisible to other EKS pod
    // replicas, breaking horizontal scaling. RedisTemplate stores cache entries in the shared
    // ElastiCache cluster, making them accessible to all pods in the EKS deployment.
    // Connection details (REDIS_HOST, REDIS_PORT) are injected via Kubernetes ConfigMap/Secret.
    // IRSA (IAM Roles for Service Accounts) provides secure, credential-free ElastiCache access.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cz-java-0070: Redis hash key used to namespace booking cache entries in ElastiCache.
    // All booking cache entries are stored under this hash key in the shared Redis cluster.
    private static final String BOOKING_CACHE_KEY = "bookingCache";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069: HttpSession is now backed by Amazon ElastiCache (Redis) via
            // Spring Session (spring-session-data-redis). Session data is stored externally
            // in Redis, making it visible to all EKS pod replicas and surviving pod restarts.
            // Kubernetes Secret injects REDIS_HOST / REDIS_PORT; IRSA provides IAM-based access.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 [Lines 34-35]: session.setAttribute calls are now intercepted by
        // Spring Session (@EnableRedisHttpSession in RedisSessionConfig) and persisted to
        // Amazon ElastiCache (Redis) on EKS — no longer stored in-memory on a single pod.
        // Session survives pod restarts and is consistent across all EKS pod replicas.
        session.setAttribute("lastBooking", booking); // cz-java-0069: externalized to Redis
        session.setAttribute("guestName", guestName); // cz-java-0069: externalized to Redis

        // cz-java-0070: Store booking in Amazon ElastiCache (Redis) via RedisTemplate.
        // Replaces the former local HashMap (bookingCache) which was instance-local and
        // caused cache inconsistency across horizontally-scaled EKS pod replicas.
        redisTemplate.opsForHash().put(BOOKING_CACHE_KEY, booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0063: HttpSession is now backed by Amazon ElastiCache (Redis) via
            // Spring Session. Reading session attributes retrieves data from the shared Redis
            // store, consistent across all EKS pod replicas regardless of which pod handles
            // the request.
            HttpSession session) {

        // cz-java-0063: Session attribute read now fetches from Redis (ElastiCache),
        // not from instance-local memory — consistent across all pods in the cluster.
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
        // cz-java-0057: Replaced hardcoded absolute file path "/var/legacy/reports/" with
        // environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap / EKS Pod spec
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
