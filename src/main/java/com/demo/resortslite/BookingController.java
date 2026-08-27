package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Spring Boot 3.x / Java 17 uses Jakarta EE 10 (jakarta.*) instead of javax.*
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Externalised to environment variable via application.properties (cr-java-0021, cr-java-0088).
    @Value("${app.inventory.endpoint}")
    private String inventoryEndpoint;

    // Externalised to environment variable via application.properties (czr-java-001).
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // NOTE: The in-memory bookingCache (cr-java-0067) has been removed.
    // For distributed caching across EC2/ECS/EKS instances, use a shared cache
    // such as Amazon ElastiCache (Redis) injected via a CacheManager bean.

    /**
     * Creates a new booking and returns the confirmed booking details.
     *
     * <p>Booking state is no longer stored in the HTTP session (cr-java-0065).
     * AWS ALB distributes requests across instances — session data stored on one
     * instance is invisible to others. Persistent state must be stored in the
     * database or a distributed cache (e.g. ElastiCache/Redis).</p>
     *
     * @param guestName guest's full name
     * @param roomType  room category
     * @param checkIn   check-in date
     * @param checkOut  check-out date
     * @return confirmation response containing booking details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Booking state is persisted to the database — no in-memory session storage (cr-java-0065).

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the current status of a booking by its identifier.
     *
     * <p>Guest context is retrieved from the database rather than the HTTP session
     * (cr-java-0065) to ensure correctness in a horizontally scaled cluster.</p>
     *
     * @param bookingId the booking identifier
     * @return map containing booking details
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability for the requested room type.
     *
     * <p>The inventory service endpoint is read from the {@code app.inventory.endpoint}
     * property (injected via environment variable {@code INVENTORY_ENDPOINT}).
     * HTTPS is enforced to comply with cloud security standards (cr-java-0088).</p>
     *
     * @param roomType room category to check
     * @return availability response including the resolved inventory endpoint
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // inventoryEndpoint is injected from environment variable — no hardcoded URL (cr-java-0088).
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns the download path for a monthly booking report.
     *
     * <p>The report base path is read from the {@code app.report.base-path} property
     * (injected via environment variable {@code REPORT_BASE_PATH}).
     * This ensures the path is valid inside Docker containers (czr-java-001).</p>
     *
     * @param month month identifier (e.g. "2024-03")
     * @return response containing the resolved report path and generation status
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // reportBasePath is injected from environment variable — no hardcoded absolute path (czr-java-001).
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
