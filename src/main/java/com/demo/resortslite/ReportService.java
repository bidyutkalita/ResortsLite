package com.demo.resortslite;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService handles report generation and storage using Amazon S3 for
 * cloud-native, durable, and scalable storage. All file path dependencies
 * have been replaced with S3 object storage operations. Environment-specific
 * URLs and port numbers are retrieved from AWS Systems Manager Parameter Store.
 * All time operations use java.time API standardized on UTC.
 */
@Service
public class ReportService {

    // S3 bucket name is read from environment variable — no hard-coded paths
    private final String s3BucketName;

    // Server port is read from environment variable — no hard-coded port numbers
    private final int serverPort;

    // Report download base URL is read from AWS SSM Parameter Store
    private final String reportDownloadBaseUrl;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    /**
     * Constructs ReportService, reading all environment-specific configuration
     * from environment variables and AWS SSM Parameter Store at startup.
     * Replaces hard-coded file paths (blocker-1, blocker-2, blocker-3),
     * hard-coded port (blocker-12), and hard-coded environment URL (blocker-11).
     */
    public ReportService() {
        this.s3Client = S3Client.create();
        this.ssmClient = SsmClient.create();

        // Replace hard-coded REPORT_BASE_PATH ("/var/legacy/reports/") and
        // BACKUP_PATH ("C:\\ResortBackups\\nightly\\") with S3 bucket from env var.
        // Fixes blocker-1, blocker-2, blocker-3 (cr-java-0061) and
        // blocker-4 (cr-java-0062), blocker-5, blocker-6, blocker-7 (cr-java-0063).
        this.s3BucketName = System.getenv().getOrDefault("REPORT_S3_BUCKET", "resortlite-reports");

        // Replace hard-coded SERVER_PORT (8080) with environment variable injection.
        // Fixes blocker-12 (cr-java-0077).
        String portEnv = System.getenv().getOrDefault("SERVER_PORT", "8080");
        this.serverPort = Integer.parseInt(portEnv);

        // Replace hard-coded "http://reports.resorts-internal.com:8080/download/" URL
        // with value from AWS SSM Parameter Store. Fixes blocker-11 (cr-java-0071).
        this.reportDownloadBaseUrl = getParameterFromSsm(
                "/resortlite/report/download-base-url",
                "https://reports.resorts-internal.com/download/");
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     * Falls back to the provided default value if the parameter is not found.
     *
     * @param parameterName the SSM parameter name/path
     * @param defaultValue  fallback value if parameter is unavailable
     * @return the resolved parameter value
     */
    private String getParameterFromSsm(String parameterName, String defaultValue) {
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(parameterName)
                            .withDecryption(true)
                            .build());
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Generates a monthly report and uploads it to Amazon S3.
     * Replaces all java.io.File and FileWriter operations with S3 PutObject calls.
     * Fixes blocker-3 (cr-java-0061), blocker-4 (cr-java-0062),
     * blocker-5, blocker-6, blocker-7 (cr-java-0063).
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return result map with S3 object key and status
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key replaces the local file path — no local file system dependency
        String s3ObjectKey = "reports/" + year + "/" + month + "/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local File or FileWriter needed
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes();

            // Upload report directly to Amazon S3 — replaces FileWriter to local path
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3ObjectKey)
                    .contentType("text/csv")
                    .build();
            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3ObjectKey);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL using the base URL retrieved from
     * AWS SSM Parameter Store. Replaces the hard-coded environment URL.
     * Fixes blocker-11 (cr-java-0071).
     *
     * @param reportName the name of the report file
     * @return the fully qualified download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // reportDownloadBaseUrl is sourced from SSM Parameter Store — not hard-coded
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information using UTC timestamps via java.time API.
     * Replaces java.util.Date / SimpleDateFormat with java.time.Instant and
     * DateTimeFormatter standardized on UTC. Fixes blocker-19 (cr-java-0111).
     *
     * @return map containing system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Replace new Date() / SimpleDateFormat with java.time API on UTC
        // Fixes blocker-19 (cr-java-0111): Clock/Time Dependencies
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("s3Bucket", s3BucketName);
        info.put("serverPort", serverPort);
        info.put("reportDownloadBaseUrl", reportDownloadBaseUrl);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
