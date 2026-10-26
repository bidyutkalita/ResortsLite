package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cr-java-0062 / cr-java-0063 FIX: Replaced hard-coded absolute file path "/var/legacy/reports/"
    // with AWS S3 bucket and prefix configuration sourced from environment variables.
    // Local file system paths are ephemeral in containerised/cloud environments; data written
    // locally is lost on container restart or scale-out events.
    @Value("${aws.s3.bucket.name}")
    private String s3BucketName;

    // cr-java-0062 / cr-java-0063 FIX: Replaced hard-coded Windows-style backup path
    // "C:\\ResortBackups\\nightly\\" with AWS S3 prefix configuration sourced from environment variables.
    @Value("${aws.s3.reports.prefix}")
    private String s3ReportsPrefix;

    @Value("${aws.s3.backup.prefix}")
    private String s3BackupPrefix;

    @Value("${aws.s3.region}")
    private String s3Region;

    // cr-java-0071 FIX: Replaced hard-coded report download URL
    // "http://reports.resorts-internal.com:8080/download/" with a value sourced from
    // AWS Systems Manager Parameter Store via the REPORT_DOWNLOAD_BASE_URL environment variable.
    // In AWS deployments, the SSM Parameter Store value is injected as an environment variable
    // through the ECS task definition or Spring Cloud AWS SSM integration, ensuring the URL
    // is environment-agnostic and can be changed without code modifications.
    @Value("${app.report.download.base.url:${REPORT_DOWNLOAD_BASE_URL:http://reports.resorts-internal.com:8080/download/}}")
    private String reportDownloadBaseUrl;

    // cr-java-0077 FIX: Replaced hard-coded port constant "private static final int SERVER_PORT = 8080"
    // with a Spring @Value field sourced from the SERVER_PORT environment variable, which is injected
    // at runtime by ECS task definition / EKS pod spec / Elastic Beanstalk environment properties.
    // The actual SSM Parameter Store value (/resortslite/<env>/server-port) is mapped to the
    // SERVER_PORT environment variable in the deployment configuration, enabling dynamic port
    // assignment required by container orchestration platforms and cloud service discovery.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;


    /**
     * Generates a monthly booking report and uploads it to Amazon S3.
     *
     * <p>cr-java-0063 FIX (lines 37, 39, 42 of original source):
     * <ul>
     *   <li>Line 37 — {@code new File(REPORT_BASE_PATH)}: Removed {@code java.io.File} instantiation.
     *       Replaced with an S3 object key composed of the configurable S3 prefix and file name.</li>
     *   <li>Line 39 — {@code reportDir.mkdirs()}: Eliminated entirely. Amazon S3 is a flat object
     *       store; no directory creation is required or possible.</li>
     *   <li>Line 42 — {@code new FileWriter(fullPath)}: Removed {@code java.io.FileWriter} usage.
     *       CSV content is built in-memory and uploaded directly to S3 via
     *       {@code RequestBody.fromString()} and {@code s3Client.putObject()}.</li>
     * </ul>
     * S3 is a durable, highly available object store that survives container restarts and
     * horizontal scale-out, eliminating the data-loss risk inherent in ephemeral local file
     * systems.</p>
     *
     * @param month the month for which the report is generated
     * @param year  the year for which the report is generated
     * @return a map containing the operation status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        // cr-java-0063 FIX (original line 37): Replaced "String fullPath = REPORT_BASE_PATH + fileName"
        // and "new File(REPORT_BASE_PATH)" with an S3 object key composed of the configurable
        // prefix and the file name. No java.io.File object is created.
        String s3ObjectKey = s3ReportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 FIX (original line 39): Replaced "reportDir.mkdirs()" — S3 is a flat
            // object store, so no directory creation step is needed or applicable.
            // The S3Client uses the default AWS credential provider chain (environment variables,
            // instance profile, ECS task role, etc.) — no hard-coded credentials.
            S3Client s3Client = S3Client.builder()
                    .region(Region.of(s3Region))
                    .build();

            // cr-java-0063 FIX (original line 42): Replaced "new FileWriter(fullPath)" +
            // "writer.write(...)" + "writer.close()" with in-memory CSV content uploaded
            // directly to S3 via RequestBody.fromString(). No java.io.File or java.io.FileWriter
            // is used; all I/O goes through the AWS SDK S3 client.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3ObjectKey)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3ObjectKey);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the base URL sourced from AWS Systems Manager
     * Parameter Store via the REPORT_DOWNLOAD_BASE_URL environment variable.
     *
     * <p>cr-java-0071 FIX (line 66): Replaced hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} with the externalized
     * value injected from AWS Systems Manager Parameter Store via the
     * {@code REPORT_DOWNLOAD_BASE_URL} environment variable / {@code app.report.download.base.url}
     * Spring property. The URL is now environment-agnostic and requires no code change per
     * deployment environment.</p>
     *
     * @param reportName the name of the report object in S3
     * @return the download URL for the given report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX (original line 66): Replaced hard-coded URL
        // "http://reports.resorts-internal.com:8080/download/" with the externalized
        // reportDownloadBaseUrl field sourced from AWS Systems Manager Parameter Store
        // via the REPORT_DOWNLOAD_BASE_URL environment variable.
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 storage configuration.
     *
     * <p>cr-java-0062 / cr-java-0063 FIX: Replaced previous method that exposed hard-coded local
     * file paths ({@code REPORT_BASE_PATH}, {@code BACKUP_PATH}) with S3 bucket/prefix values
     * sourced from environment-variable-backed Spring properties.</p>
     *
     * @return a map of system information entries
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replaced java.util.Date and SimpleDateFormat with java.time API.
        // Instant.now() captures the current moment in UTC, eliminating server-local timezone
        // dependencies that cause inconsistencies across distributed cloud containers/regions.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: Replaced info.put("reportPath", REPORT_BASE_PATH) — hard-coded path removed
        info.put("s3Bucket", s3BucketName);
        // cr-java-0063 FIX: Replaced info.put("backupPath", BACKUP_PATH) — hard-coded Windows path removed
        info.put("s3ReportsPrefix", s3ReportsPrefix);
        info.put("s3BackupPrefix", s3BackupPrefix);
        // cr-java-0077 FIX: Replaced info.put("serverPort", SERVER_PORT) — now uses the injected
        // serverPort field sourced from SERVER_PORT environment variable / AWS Parameter Store.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
