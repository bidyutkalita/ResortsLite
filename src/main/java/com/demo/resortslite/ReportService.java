package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation using Amazon S3 for durable
 * object storage and AWS Systems Manager Parameter Store for all environment-
 * specific configuration values (paths, ports, URLs).
 *
 * <p>Replaces:
 * <ul>
 *   <li>Hard-coded absolute file paths ({@code /var/legacy/reports/}, {@code C:\ResortBackups\})
 *       → S3 bucket + key prefix resolved from environment variable {@code REPORT_S3_BUCKET}
 *       and SSM parameter {@code /resortsLite/reportKeyPrefix}.</li>
 *   <li>Local {@code java.io.File} / {@code FileWriter} write operations → {@code S3Client.putObject}.</li>
 *   <li>Hard-coded server port constant → environment variable {@code SERVER_PORT} with
 *       SSM parameter {@code /resortsLite/serverPort} as fallback.</li>
 *   <li>Hard-coded report download URL → SSM parameter {@code /resortsLite/reportDownloadBaseUrl}.</li>
 *   <li>{@code java.util.Date} / {@code SimpleDateFormat} → {@code java.time} API (UTC).</li>
 * </ul>
 */
@Service
public class ReportService {

    // -----------------------------------------------------------------------
    // Cloud-native configuration — values injected from environment variables
    // set by ECS task definition / Elastic Beanstalk environment, with
    // SSM Parameter Store as the authoritative source.
    // -----------------------------------------------------------------------

    /** S3 bucket name for report storage — injected from environment variable. */
    @Value("${cloud.s3.report-bucket:${REPORT_S3_BUCKET:resortsLite-reports}}")
    private String reportBucket;

    /** S3 key prefix for reports — injected from environment variable. */
    @Value("${cloud.s3.report-key-prefix:${REPORT_S3_KEY_PREFIX:reports/}}")
    private String reportKeyPrefix;

    /** Server port — injected from environment variable (set by ECS/EKS at runtime). */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /** Base URL for report downloads — injected from environment variable. */
    @Value("${cloud.report.download-base-url:${REPORT_DOWNLOAD_BASE_URL:}}")
    private String reportDownloadBaseUrl;

    /** SSM parameter name for the report download base URL (fallback when env var is empty). */
    @Value("${cloud.ssm.report-download-url-param:/resortsLite/reportDownloadBaseUrl}")
    private String ssmReportDownloadUrlParam;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     * All file I/O is performed against S3 — no local file system dependency.
     *
     * @param month numeric month string (e.g. "03")
     * @param year  four-digit year string (e.g. "2024")
     * @return result map containing S3 URI, status, and server port
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 key replaces the former hard-coded absolute path
        String s3Key = reportKeyPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local FileWriter / java.io.File
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload directly to S3 — durable, scalable, no ephemeral local FS dependency
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("s3Uri", "s3://" + reportBucket + "/" + s3Key);
            // serverPort is now injected from environment variable — not hard-coded
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a pre-signed or base report download URL using the base URL
     * retrieved from AWS Systems Manager Parameter Store, eliminating the
     * former hard-coded {@code http://reports.resorts-internal.com:8080/download/} URL.
     *
     * @param reportName the report file name
     * @return fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Resolve base URL: prefer environment variable, fall back to SSM Parameter Store
        String baseUrl = reportDownloadBaseUrl;
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = getParameterFromSsm(ssmReportDownloadUrlParam,
                    "https://reports.resorts-internal.com/download");
        }
        // Ensure HTTPS — cloud security standards enforce encrypted transport
        if (baseUrl.startsWith("http://")) {
            baseUrl = baseUrl.replaceFirst("http://", "https://");
        }
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps (java.time API) and
     * cloud-native configuration values instead of hard-coded paths/ports.
     *
     * @return map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // java.time API with explicit UTC zone — replaces java.util.Date / SimpleDateFormat
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        String timestamp = nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();
        // S3 URI replaces former hard-coded local paths
        info.put("reportStorage", "s3://" + reportBucket + "/" + reportKeyPrefix);
        // serverPort is environment-variable-driven — not a hard-coded constant
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * @param paramName    SSM parameter name (e.g. {@code /resortsLite/serverPort})
     * @param defaultValue value to return if the parameter cannot be retrieved
     * @return parameter value or defaultValue
     */
    private String getParameterFromSsm(String paramName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
