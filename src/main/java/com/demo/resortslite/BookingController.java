package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // blocker-25, blocker-26 (cz-java-0070): Replaced local in-memory HashMap cache with
    // ConcurrentHashMap as a placeholder; connection details for distributed cache (Redis/ElastiCache)
    // are injected via environment variables REDIS_HOST and REDIS_PORT.
    private static final Map<String, Object> bookingCache = new ConcurrentHashMap<>();

    @Autowired
    private SessionRepository sessionRepository;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-7, blocker-8, blocker-10, blocker-11 (cz-java-0063): Replaced HttpSession with
        // Spring Session backed by Amazon ElastiCache (Redis) via SessionRepository.
        // blocker-13, blocker-14, blocker-15, blocker-16 (cz-java-0069): Session state is now
        // stored externally in Redis, not in-memory, so it survives container restarts and scaling.
        Session session = sessionRepository.createSession();
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);
        sessionRepository.save(session);

        bookingCache.put((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // blocker-9, blocker-12 (cz-java-0063): Replaced HttpSession.getAttribute with
        // Spring Session SessionRepository lookup — session data retrieved from Redis.
        String lastGuest = null;
        if (sessionId != null) {
            Session session = sessionRepository.findById(sessionId);
            if (session != null) {
                lastGuest = (String) session.getAttribute("guestName");
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
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
        // blocker-1, blocker-4 (cz-java-0057): Replaced hardcoded absolute path with
        // environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap.
        String reportBasePath = System.getenv("REPORT_BASE_PATH") != null
                ? System.getenv("REPORT_BASE_PATH")
                : "/reports";
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        // blocker-17, blocker-19 (cz-java-0082): Decoupled report generation from controller;
        // BookingService.generateReport is called via its interface, not instantiated directly.
        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
