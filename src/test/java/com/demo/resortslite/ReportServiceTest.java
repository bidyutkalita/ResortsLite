package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @InjectMocks
    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Use a temp directory so file I/O tests are isolated
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                tempDir.toString() + "/");
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport tests
    // -----------------------------------------------------------------------

    @Test
    void generateMonthlyReport_withValidMonthYear_returnsGeneratedStatus() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
        assertEquals("generated", result.get("status"));
    }

    @Test
    void generateMonthlyReport_withValidMonthYear_returnsCorrectPath() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        String path = (String) result.get("path");
        assertNotNull(path);
        assertTrue(path.contains("resort_report_03_2024.csv"),
                "Path should contain the expected file name");
    }

    @Test
    void generateMonthlyReport_createsFileOnDisk() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("04", "2024");

        // Assert
        String path = (String) result.get("path");
        File reportFile = new File(path);
        assertTrue(reportFile.exists(), "Report file should exist on disk");
    }

    @Test
    void generateMonthlyReport_fileContainsCsvHeader() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");

        // Assert
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"),
                "CSV should contain header row");
    }

    @Test
    void generateMonthlyReport_fileContainsSampleData() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BK-001"), "CSV should contain sample booking BK-001");
        assertTrue(content.contains("BK-002"), "CSV should contain sample booking BK-002");
    }

    @Test
    void generateMonthlyReport_createsDirectoryIfNotExists() {
        // Arrange — use a nested subdirectory that doesn't exist yet
        String nestedPath = tempDir.toString() + "/nested/reports/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", nestedPath);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        File dir = new File(nestedPath);
        assertTrue(dir.exists(), "Directory should be created if it doesn't exist");
    }

    @Test
    void generateMonthlyReport_withDifferentMonthYear_generatesCorrectFileName() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("12", "2023");

        // Assert
        String path = (String) result.get("path");
        assertTrue(path.endsWith("resort_report_12_2023.csv"),
                "File name should match month and year");
    }

    @Test
    void generateMonthlyReport_resultMapHasTwoKeys() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2024");

        // Assert
        assertEquals(2, result.size(), "Result should have 'status' and 'path' keys");
        assertTrue(result.containsKey("status"));
        assertTrue(result.containsKey("path"));
    }

    @Test
    void generateMonthlyReport_whenDirectoryIsReadOnly_returnsErrorStatus() {
        // Arrange — set an invalid path that cannot be created
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                "/proc/invalid_readonly_path_xyz/");

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("08", "2024");

        // Assert
        assertEquals("error", result.get("status"),
                "Should return error status when directory cannot be created");
        assertTrue(result.containsKey("message"),
                "Error result should contain 'message' key");
    }

    @Test
    void generateMonthlyReport_pathUsesConfiguredBasePath() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("09", "2024");

        // Assert
        String path = (String) result.get("path");
        assertTrue(path.startsWith(tempDir.toString()),
                "Report path should start with the configured base path");
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl tests
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withValidReportName_returnsHttpsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_03_2024.csv");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://"), "URL should use HTTPS");
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_04_2024.csv");

        // Assert
        assertTrue(url.contains("report_04_2024.csv"),
                "URL should contain the report file name");
    }

    @Test
    void buildReportDownloadUrl_containsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("my_report.csv");

        // Assert
        assertTrue(url.contains("/download/"), "URL should contain '/download/' path segment");
    }

    @Test
    void buildReportDownloadUrl_returnsFullyQualifiedUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.csv");

        // Assert
        assertEquals("https://reports.resorts-internal.com/download/test_report.csv", url);
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportNames_returnsCorrectUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("jan_report.csv");
        String url2 = reportService.buildReportDownloadUrl("feb_report.csv");

        // Assert
        assertNotEquals(url1, url2, "Different report names should produce different URLs");
        assertTrue(url1.endsWith("jan_report.csv"));
        assertTrue(url2.endsWith("feb_report.csv"));
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://"));
    }

    // -----------------------------------------------------------------------
    // getSystemInfo tests
    // -----------------------------------------------------------------------

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"), "System info should contain 'reportPath'");
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_containsGeneratedAt() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"), "System info should contain 'generatedAt'");
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_reportPathMatchesConfiguredPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) info.get("reportPath");
        assertEquals(tempDir.toString() + "/", reportPath,
                "Report path in system info should match configured base path");
    }

    @Test
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String generatedAt = (String) info.get("generatedAt");
        assertNotNull(generatedAt);
        // Verify format: yyyy-MM-dd HH:mm:ss
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match format 'yyyy-MM-dd HH:mm:ss'");
    }

    @Test
    void getSystemInfo_hasTwoKeys() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(2, info.size(), "System info should have 'reportPath' and 'generatedAt' keys");
    }

    @Test
    void getSystemInfo_calledMultipleTimes_returnsConsistentReportPath() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertEquals(info1.get("reportPath"), info2.get("reportPath"),
                "Report path should be consistent across calls");
    }
}
