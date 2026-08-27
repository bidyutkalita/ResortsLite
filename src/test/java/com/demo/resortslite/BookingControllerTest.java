package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingController, "inventoryEndpoint",
                "https://inventory.example.com/api");
        ReflectionTestUtils.setField(bookingController, "reportBasePath",
                "/tmp/reports/");
    }

    // -----------------------------------------------------------------------
    // createBooking tests
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "John Doe");
        mockBooking.put("roomType", "SUITE");
        mockBooking.put("checkIn", "2024-03-01");
        mockBooking.put("checkOut", "2024-03-05");
        when(bookingService.createBooking("John Doe", "SUITE", "2024-03-01", "2024-03-05"))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "John Doe", "SUITE", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_responseContainsBookingDetails() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Jane Smith");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Jane Smith", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        assertNotNull(response.get("booking"));
        @SuppressWarnings("unchecked")
        Map<String, Object> booking = (Map<String, Object>) response.get("booking");
        assertEquals("BK-ABCD1234", booking.get("bookingId"));
    }

    @Test
    void createBooking_callsBookingServiceCreateBooking() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        when(bookingService.createBooking("Alice", "VILLA", "2024-05-01", "2024-05-10"))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Alice", "VILLA", "2024-05-01", "2024-05-10");

        // Assert
        verify(bookingService, times(1))
                .createBooking("Alice", "VILLA", "2024-05-01", "2024-05-10");
    }

    @Test
    void createBooking_responseHasTwoKeys() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "STANDARD", "2024-06-01", "2024-06-02");

        // Assert
        assertEquals(2, response.size(), "Response should have 'status' and 'booking' keys");
        assertTrue(response.containsKey("status"));
        assertTrue(response.containsKey("booking"));
    }

    // -----------------------------------------------------------------------
    // getBookingStatus tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_withValidId_returnsBookingIdInResponse() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-12345678");
        mockDetails.put("guest", "John Doe");
        when(bookingService.getBookingById("BK-12345678")).thenReturn(mockDetails);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-12345678");

        // Assert
        assertNotNull(response);
        assertEquals("BK-12345678", response.get("bookingId"));
    }

    @Test
    void getBookingStatus_responseContainsDetails() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("guest", "Jane Doe");
        when(bookingService.getBookingById("BK-ABCDEF12")).thenReturn(mockDetails);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-ABCDEF12");

        // Assert
        assertNotNull(response.get("details"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) response.get("details");
        assertEquals("Jane Doe", details.get("guest"));
    }

    @Test
    void getBookingStatus_callsBookingServiceGetBookingById() {
        // Arrange
        when(bookingService.getBookingById("BK-TEST1234")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-TEST1234");

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TEST1234");
    }

    @Test
    void getBookingStatus_responseHasTwoKeys() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-ANY");

        // Assert
        assertEquals(2, response.size());
        assertTrue(response.containsKey("bookingId"));
        assertTrue(response.containsKey("details"));
    }

    @Test
    void getBookingStatus_whenBookingNotFound_returnsErrorInDetails() {
        // Arrange
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put("error", "Booking not found: BK-MISSING");
        when(bookingService.getBookingById("BK-MISSING")).thenReturn(errorMap);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-MISSING");

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) response.get("details");
        assertTrue(details.containsKey("error"));
    }

    // -----------------------------------------------------------------------
    // checkAvailability tests
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_withAvailableRoom_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(response);
        assertEquals(true, response.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, response.get("available"));
    }

    @Test
    void checkAvailability_responseContainsRoomType() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("DELUXE", response.get("roomType"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("VILLA");

        // Assert
        assertEquals("https://inventory.example.com/api", response.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_responseHasThreeKeys() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertEquals(3, response.size());
        assertTrue(response.containsKey("roomType"));
        assertTrue(response.containsKey("inventoryEndpoint"));
        assertTrue(response.containsKey("available"));
    }

    @Test
    void checkAvailability_callsBookingServiceIsRoomAvailable() {
        // Arrange
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        bookingController.checkAvailability("STANDARD");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("STANDARD");
    }

    // -----------------------------------------------------------------------
    // downloadReport tests
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_responseContainsReportPath() {
        // Arrange
        when(bookingService.generateReport("2024-03")).thenReturn("Report generation triggered for: 2024-03 via https://payment.example.com/api");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-03");

        // Assert
        assertNotNull(response);
        assertNotNull(response.get("reportPath"));
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("2024-05")).thenReturn("Report triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-05");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.contains("2024-05"), "Report path should contain the month");
    }

    @Test
    void downloadReport_reportPathEndsWithPdf() {
        // Arrange
        when(bookingService.generateReport("2024-06")).thenReturn("Report triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-06");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.endsWith("_bookings.pdf"), "Report path should end with '_bookings.pdf'");
    }

    @Test
    void downloadReport_reportPathUsesConfiguredBasePath() {
        // Arrange
        when(bookingService.generateReport("2024-07")).thenReturn("Report triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-07");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.startsWith("/tmp/reports/"), "Report path should start with configured base path");
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        String expectedMessage = "Report generation triggered for: 2024-08 via https://payment.example.com/api";
        when(bookingService.generateReport("2024-08")).thenReturn(expectedMessage);

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-08");

        // Assert
        assertEquals(expectedMessage, response.get("message"));
    }

    @Test
    void downloadReport_callsBookingServiceGenerateReport() {
        // Arrange
        when(bookingService.generateReport("2024-09")).thenReturn("triggered");

        // Act
        bookingController.downloadReport("2024-09");

        // Assert
        verify(bookingService, times(1)).generateReport("2024-09");
    }

    @Test
    void downloadReport_responseHasTwoKeys() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-10");

        // Assert
        assertEquals(2, response.size());
        assertTrue(response.containsKey("reportPath"));
        assertTrue(response.containsKey("message"));
    }
}
