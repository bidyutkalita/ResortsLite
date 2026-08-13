package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
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

    // Blocker-1/2/3 (cr-java-0061): Hard-coded file paths replaced with S3 bucket/prefix
    // configured via environment variables — no host file system dependency.
    @Value("${cloud.aws.s3.bucket-name:resorts-lite-reports}")
    private String s3BucketName;

    // Blocker-12 (cr-java-0077): Hard-coded port replaced with environment variable injection.
    // Value is resolved at runtime from the environment (ECS task definition / Elastic Beanstalk).
    @Value("${server.port:8080}")
    private int serverPort;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     * Blocker-4 (cr-java-0062): Local file write replaced with S3 PutObject.
     * Blocker-5/6/7 (cr-java-0063): java.io.File usage replaced with S3 SDK calls.
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Blocker-1 (cr-java-0061): S3 key replaces hard-coded absolute path /var/legacy/reports/
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system required
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes();

            // Blocker-2/3/4/5/6/7 (cr-java-0061/0062/0063): Upload directly to S3
            // instead of writing to /var/legacy/reports/ or C:\ResortBackups\nightly\
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            // Blocker-12 (cr-java-0077): Port sourced from environment variable, not hard-coded
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the endpoint retrieved from
     * AWS Systems Manager Parameter Store.
     * Blocker-11 (cr-java-0071): Hard-coded URL replaced with SSM Parameter Store lookup.
     */
    public String buildReportDownloadUrl(String reportName) {
        // Blocker-11 (cr-java-0071): Retrieve environment-specific base URL from SSM
        // Parameter Store instead of embedding "http://reports.resorts-internal.com:8080"
        String paramName = System.getenv().getOrDefault(
                "REPORT_BASE_URL_PARAM", "/resortslite/report/base-url");
        String baseUrl;
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder().name(paramName).withDecryption(false).build());
            baseUrl = response.parameter().value();
        } catch (Exception e) {
            // Fall back to environment variable if SSM is unavailable
            baseUrl = System.getenv().getOrDefault("REPORT_BASE_URL", "https://reports.resorts-internal.com");
        }
        return baseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps.
     * Blocker-19 (cr-java-0111): java.util.Date / SimpleDateFormat replaced with
     * java.time.Instant (UTC) to eliminate timezone inconsistencies across cloud regions.
     */
    public Map<String, Object> getSystemInfo() {
        // Blocker-19 (cr-java-0111): Use java.time.Instant for UTC-standardised timestamp
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // Blocker-1/2/3 (cr-java-0061): Report location is now S3, not a local path
        info.put("s3Bucket", s3BucketName);
        info.put("reportPrefix", "reports/");
        // Blocker-12 (cr-java-0077): Port sourced from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
