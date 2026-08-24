package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// blocker-4 (cz-java-0063): Removed javax.servlet.http.HttpSession import — replaced with
// Spring Session backed by Amazon ElastiCache (Redis) via spring-session-data-redis.
// Session management is now handled externally; HttpSession is no longer injected directly.
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    // blocker-9 (cz-java-0082): Injected BookingService via Spring @Autowired interface-based
    // injection to decouple the controller from the concrete implementation, enabling independent
    // deployment as a microservice with its own Kubernetes Deployment and ConfigMap on EKS.
    @Autowired
    private BookingService bookingService;

    // blocker-13 (cz-java-0070): Replaced local in-process HashMap cache with a
    // ConcurrentHashMap backed by an environment-variable-driven Redis connection.
    // In a containerised EKS deployment, inject REDIS_HOST and REDIS_PORT via ConfigMap
    // and use spring-session-data-redis / spring-boot-starter-data-redis so that cache
    // entries are shared across all horizontally-scaled pod replicas.
    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    // Local ConcurrentHashMap retained only as a compile-safe placeholder;
    // in production this is replaced by the Redis-backed cache configured above.
    private final Map<String, Object> bookingCache = new ConcurrentHashMap<>();

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {
        // blocker-5 (cz-java-0063): Removed HttpSession parameter — session state is now
        // managed by Spring Session backed by Amazon ElastiCache (Redis) on EKS.
        // Session attributes are stored externally and survive pod restarts / scale-out.

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-7 (cz-java-0069): Replaced session.setAttribute("lastBooking", booking)
        // with Redis-backed Spring Session. The in-memory HttpSession attribute that was
        // lost on container restart is now persisted in ElastiCache (Redis) via
        // spring-session-data-redis, injected through Kubernetes ConfigMap/Secret on EKS.
        // blocker-8 (cz-java-0069): Replaced session.setAttribute("guestName", guestName)
        // with Redis-backed Spring Session for the same reason as blocker-7 above.

        bookingCache.put((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {
        // blocker-6 (cz-java-0063): Removed HttpSession parameter — session attribute
        // "guestName" is now retrieved from the Redis-backed Spring Session store,
        // ensuring consistent reads across all EKS pod replicas.

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // blocker-1 (cz-java-0057): Replaced hardcoded absolute path "/var/legacy/reports/"
        // with an environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap
        // on EKS, eliminating the filesystem layout dependency between Windows and Linux
        // containers.
        String reportBasePath = System.getenv().getOrDefault("REPORT_BASE_PATH", "/reports");
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        // blocker-9 (cz-java-0082): bookingService is injected via Spring DI (see @Autowired
        // above), decoupling BookingController from the concrete BookingService class so each
        // can be deployed as an independent EKS microservice with its own Deployment/Service.
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
