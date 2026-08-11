package com.demo.resortslite;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // S3 bucket name read from environment variable (cloud-native externalized config)
    private final String s3BucketName = System.getenv().getOrDefault("REPORT_S3_BUCKET", "resorts-reports-bucket");

    // Server port externalized to environment variable — no hard-coded port
    private final int serverPort;

    // Report download base URL externalized via AWS SSM Parameter Store
    private final String reportDownloadBaseUrl;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService() {
        this.s3Client = S3Client.create();
        this.ssmClient = SsmClient.create();

        // Replace hard-coded port with environment variable injection (blocker-12: cr-java-0077)
        String portEnv = System.getenv("SERVER_PORT");
        this.serverPort = (portEnv != null && !portEnv.isEmpty()) ? Integer.parseInt(portEnv) : 8080;

        // Replace hard-coded URL with AWS SSM Parameter Store lookup (blocker-11: cr-java-0071)
        this.reportDownloadBaseUrl = resolveParameterStoreValue(
                "/resortslite/report/download-base-url",
                "https://reports.resorts-internal.com/download");
    }

    /**
     * Retrieves a configuration value from AWS SSM Parameter Store.
     * Falls back to the provided default if the parameter is unavailable.
     */
    private String resolveParameterStoreValue(String parameterName, String defaultValue) {
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
     * Generates a monthly report and uploads it to Amazon S3 instead of writing
     * to the local file system (blockers 1-7: cr-java-0061, cr-java-0062, cr-java-0063).
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // Upload directly to Amazon S3 (replaces local FileWriter + File operations)
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();
            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the base URL retrieved from AWS SSM Parameter Store
     * (blocker-11: cr-java-0071 — replaces hard-coded environment URL).
     */
    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps via java.time API
     * (blocker-19: cr-java-0111 — replaces java.util.Date / SimpleDateFormat).
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time.Instant with UTC for cloud-safe, timezone-independent timestamps
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("reportS3Bucket", s3BucketName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
