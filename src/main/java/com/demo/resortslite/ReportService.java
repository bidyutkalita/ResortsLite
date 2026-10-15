package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation and storage service.
 *
 * Cloud-readiness fixes applied:
 *  - cr-java-0061 (lines 23, 37, 42): Removed hard-coded absolute file paths
 *                  (/var/legacy/reports/, C:\ResortBackups\nightly\).
 *                  S3 bucket name and key prefix are injected via environment variables /
 *                  AWS SSM Parameter Store.
 *  - cr-java-0062 (line 42): Replaced local FileWriter write operations with Amazon S3
 *                  PutObject calls for durable, scalable cloud storage.
 *  - cr-java-0063 (lines 37, 39, 42): Replaced java.io.File usage with AWS SDK v2 S3Client
 *                  and S3Presigner for all storage operations.
 *  - cr-java-0071 (line 66): Replaced hard-coded report download URL with an S3 pre-signed
 *                  URL generated at runtime — no environment-specific hostname in source code.
 *  - cr-java-0077 (line 28): Removed hard-coded SERVER_PORT constant; port is now injected
 *                  from the environment variable SERVER_PORT / application.properties.
 *  - cr-java-0111 (line 70): Replaced java.util.Date / SimpleDateFormat with java.time API
 *                  (Instant, ZonedDateTime). All timestamps are standardised on UTC.
 */
@Service
public class ReportService {

    /**
     * S3 bucket name for report storage.
     * Injected from environment variable REPORT_S3_BUCKET or application.properties key
     * app.report.s3-bucket. Fixes cr-java-0061 (hard-coded file path).
     */
    @Value("${app.report.s3-bucket}")
    private String reportBucket;

    /**
     * S3 key prefix (folder) for reports.
     * Defaults to "reports/" if not configured.
     */
    @Value("${app.report.s3-prefix:reports/}")
    private String reportPrefix;

    /**
     * AWS region for S3 and pre-signer clients.
     * Defaults to us-east-1; override via AWS_REGION environment variable.
     */
    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Server port injected from the environment / application.properties.
     * Fixes cr-java-0077 (hard-coded port). ECS/EKS injects SERVER_PORT dynamically.
     */
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Pre-signed URL expiry in minutes. Defaults to 60 minutes.
     */
    @Value("${app.report.presign-ttl-minutes:60}")
    private long presignTtlMinutes;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     * Fixes cr-java-0061, cr-java-0062, cr-java-0063: no local file system usage.
     *
     * @param month numeric month (e.g. "03")
     * @param year  four-digit year (e.g. "2024")
     * @return result map with status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0061 FIX: S3 key replaces the hard-coded /var/legacy/reports/ path.
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try (S3Client s3 = buildS3Client()) {
            // Build CSV content in memory — no local file system dependency.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            byte[] csvBytes = csvContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // cr-java-0062 / cr-java-0063 FIX: Upload to S3 instead of writing to local disk.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3.putObject(putRequest, RequestBody.fromBytes(csvBytes));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", s3Key);
            // cr-java-0077 FIX: serverPort is injected — not hard-coded.
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a pre-signed S3 download URL for the given report object key.
     * Fixes cr-java-0071: no hard-coded environment-specific URL in source code.
     *
     * @param reportName S3 object key (or just the file name — prefix is prepended)
     * @return HTTPS pre-signed URL valid for {@code presignTtlMinutes} minutes
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Generate a pre-signed S3 URL at runtime.
        // No hard-coded hostname or port — the URL is environment-agnostic.
        String s3Key = reportName.startsWith(reportPrefix) ? reportName : reportPrefix + reportName;

        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(presignTtlMinutes))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();
        }
    }

    /**
     * Returns system information using UTC timestamps.
     * Fixes cr-java-0111: java.util.Date / SimpleDateFormat replaced with java.time API
     * standardised on UTC.
     *
     * @return system info map
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Use java.time.Instant + ZonedDateTime with UTC zone.
        // Eliminates server-local timezone dependency across distributed cloud instances.
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 FIX: Expose S3 bucket/prefix instead of local file paths.
        info.put("reportBucket", reportBucket);
        info.put("reportPrefix", reportPrefix);
        // cr-java-0077 FIX: serverPort is injected from environment.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
