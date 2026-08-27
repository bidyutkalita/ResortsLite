package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingService, "paymentApi", "https://payment.example.com/api");
    }

    // -----------------------------------------------------------------------
    // createBooking tests
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsBookingMap() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("John Doe", "SUITE", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(result);
        assertEquals("John Doe", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-03-01", result.get("checkIn"));
        assertEquals("2024-03-05", result.get("checkOut"));
        assertNotNull(result.get("bookingId"));
        assertNotNull(result.get("confirmationCode"));
    }

    @Test
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Jane Smith", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_confirmationCodeIsNotEmpty() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Alice", "VILLA", "2024-05-01", "2024-05-10");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        assertFalse(confirmCode.isEmpty(), "Confirmation code should not be empty");
    }

    @Test
    void createBooking_confirmationCodeIsSha256Length() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Bob", "STANDARD", "2024-06-01", "2024-06-02");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        // SHA-256 hex digest is always 64 characters
        assertEquals(64, confirmCode.length(), "SHA-256 confirmation code should be 64 hex chars");
    }

    @Test
    void createBooking_jdbcTemplateUpdateIsCalled() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Test Guest", "SUITE", "2024-07-01", "2024-07-05");

        // Assert
        verify(jdbcTemplate, times(1)).update(
                eq("INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)"),
                anyString(), eq("Test Guest"), eq("SUITE"), eq("2024-07-01"), eq("2024-07-05")
        );
    }

    @Test
    void createBooking_withStandardRoom_returnsCorrectRoomType() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Guest", "STANDARD", "2024-01-01", "2024-01-02");

        // Assert
        assertEquals("STANDARD", result.get("roomType"));
    }

    // -----------------------------------------------------------------------
    // getBookingById tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_withValidId_returnsBookingDetails() {
        // Arrange
        Map<String, Object> mockRow = new HashMap<>();
        mockRow.put("id", "BK-12345678");
        mockRow.put("guest", "John Doe");
        mockRow.put("room", "SUITE");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-12345678"))).thenReturn(mockRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("id"));
        assertEquals("John Doe", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTFOUND")))
                .thenThrow(new RuntimeException("No results found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTFOUND");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"), "Result should contain 'error' key");
        String errorMsg = (String) result.get("error");
        assertTrue(errorMsg.contains("BK-NOTFOUND"), "Error message should contain the booking ID");
    }

    @Test
    void getBookingById_errorMessageContainsBookingNotFound() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenThrow(new RuntimeException("EmptyResultDataAccessException"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

        // Assert
        String error = (String) result.get("error");
        assertTrue(error.startsWith("Booking not found:"), "Error should start with 'Booking not found:'");
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice tests
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoomNoPeakNoLoyalty_returnsBasePrice() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoom_returnsCorrectBasePrice() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoom_returnsCorrectBasePrice() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoom_returnsCorrectBasePrice() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_usesStandardPrice() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeason_appliesMultiplier() {
        // Arrange & Act
        // STANDARD (120) * PEAK (1.5) * 1 night = 180.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");

        // Assert
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeason_appliesDiscount() {
        // Arrange & Act
        // STANDARD (120) * OFF (0.8) * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");

        // Assert
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyalty_appliesDiscount() {
        // Arrange & Act
        // STANDARD (120) * GOLD (0.9) * 1 night = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");

        // Assert
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesDiscount() {
        // Arrange & Act
        // STANDARD (120) * PLATINUM (0.8) * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");

        // Assert
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesDiscount() {
        // Arrange & Act
        // STANDARD (120) * DIAMOND (0.7) * 1 night = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");

        // Assert
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNights_appliesLongStayDiscount() {
        // Arrange & Act
        // STANDARD (120) * 7 nights * 0.95 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");

        // Assert
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesLargerLongStayDiscount() {
        // Arrange & Act
        // STANDARD (120) * 14 nights * 0.90 = 1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");

        // Assert
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_sixNights_noLongStayDiscount() {
        // Arrange & Act
        // STANDARD (120) * 6 nights = 720.00
        String price = bookingService.calculateRoomPrice("STANDARD", 6, "NORMAL", "NONE");

        // Assert
        assertEquals("720.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonWithDiamondLoyalty_combinesDiscounts() {
        // Arrange & Act
        // SUITE (350) * PEAK (1.5) = 525 * DIAMOND (0.7) = 367.5 * 2 nights = 735.00
        String price = bookingService.calculateRoomPrice("SUITE", 2, "PEAK", "DIAMOND");

        // Assert
        assertEquals("735.00", price);
    }

    @Test
    void calculateRoomPrice_villaOffSeasonPlatinumFourteenNights_allDiscounts() {
        // Arrange & Act
        // VILLA (600) * OFF (0.8) = 480 * PLATINUM (0.8) = 384 * 0.90 (14 nights) = 345.6 * 14 = 4838.40
        String price = bookingService.calculateRoomPrice("VILLA", 14, "OFF", "PLATINUM");

        // Assert
        assertEquals("4838.40", price);
    }

    @Test
    void calculateRoomPrice_returnsFormattedTwoDecimalString() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("DELUXE", 3, "NORMAL", "NONE");

        // Assert
        assertNotNull(price);
        assertTrue(price.matches("\\d+\\.\\d{2}"), "Price should be formatted with 2 decimal places");
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable tests
    // -----------------------------------------------------------------------

    @Test
    void isRoomAvailable_withStandard_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_withDeluxe_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_withSuite_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_withVilla_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_withUnknownType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_withEmptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_withLowercase_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("suite"));
    }

    @Test
    void isRoomAvailable_withNull_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(null));
    }

    // -----------------------------------------------------------------------
    // generateReport tests
    // -----------------------------------------------------------------------

    @Test
    void generateReport_returnsMessageContainingMonth() {
        // Act
        String result = bookingService.generateReport("2024-03");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("2024-03"), "Report message should contain the month");
    }

    @Test
    void generateReport_returnsMessageContainingPaymentApi() {
        // Act
        String result = bookingService.generateReport("2024-04");

        // Assert
        assertTrue(result.contains("https://payment.example.com/api"),
                "Report message should contain the payment API endpoint");
    }

    @Test
    void generateReport_returnsNonNullString() {
        // Act
        String result = bookingService.generateReport("2024-01");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    // -----------------------------------------------------------------------
    // RoomType enum tests (inner enum)
    // -----------------------------------------------------------------------

    @Test
    void roomType_isValid_withAllValidTypes() {
        assertTrue(BookingService.RoomType.isValid("STANDARD"));
        assertTrue(BookingService.RoomType.isValid("DELUXE"));
        assertTrue(BookingService.RoomType.isValid("SUITE"));
        assertTrue(BookingService.RoomType.isValid("VILLA"));
    }

    @Test
    void roomType_isValid_withInvalidType_returnsFalse() {
        assertFalse(BookingService.RoomType.isValid("BUNGALOW"));
    }

    @Test
    void roomType_isValid_withNull_returnsFalse() {
        assertFalse(BookingService.RoomType.isValid(null));
    }

    @Test
    void roomType_values_containsFourEntries() {
        assertEquals(4, BookingService.RoomType.values().length);
    }
}
