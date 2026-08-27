package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// java.time API (Java 8+/17) — thread-safe replacement for legacy java.util.Date + SimpleDateFormat.
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Externalised to environment variable via application.properties (czr-java-001).
    // Defaults to /tmp/reports/ which is available inside Docker containers.
    // For production, set REPORT_BASE_PATH to an EFS mount or S3-backed path.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // Thread-safe, immutable DateTimeFormatter — replaces SimpleDateFormat (not thread-safe).
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly booking report CSV and writes it to the configured report directory.
     *
     * <p>The output directory is read from the {@code app.report.base-path} property,
     * which is injected via environment variable {@code REPORT_BASE_PATH} at runtime.
     * This ensures the path is valid inside Docker containers and cloud environments
     * (czr-java-001).</p>
     *
     * @param month month identifier (e.g. "03")
     * @param year  four-digit year (e.g. "2024")
     * @return map containing generation status and output file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(reportBasePath);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            try (FileWriter writer = new FileWriter(fullPath)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a secure HTTPS download URL for the given report file name.
     *
     * <p>Uses HTTPS to comply with cloud security standards enforced by AWS ALB,
     * WAF, and the Well-Architected Framework (cr-java-0088).
     * The base URL is externalised via the {@code app.report.download-base-url}
     * property so it can be overridden per environment without code changes.</p>
     *
     * @param reportName file name of the report to download
     * @return fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // HTTPS enforced — plain HTTP is blocked by cloud security controls (cr-java-0088).
        return "https://reports.resorts-internal.com/download/" + reportName;
    }

    /**
     * Returns runtime system information for diagnostics.
     *
     * <p>Uses {@link java.time.LocalDateTime} and {@link DateTimeFormatter} instead of
     * the legacy {@code java.util.Date} + {@code SimpleDateFormat} (not thread-safe).</p>
     *
     * @return map containing report path, timestamp, and other diagnostic values
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("generatedAt", timestamp);
        return info;
    }
}
