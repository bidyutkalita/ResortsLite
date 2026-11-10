package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — generates resort reports and stores them in Amazon S3.
 *
 * <p>All file-system write dependencies (previously {@code new FileWriter(fullPath)} at
 * line 42 of the original source, along with {@code new File(REPORT_BASE_PATH)} and
 * {@code reportDir.mkdirs()}) have been replaced with Amazon S3 object storage using
 * the AWS SDK for Java v2, in accordance with remediation cr-java-0062
 * (Local File System Write Operations → Amazon S3).
 *
 * <p>cr-java-0071 FIX: The hard-coded report download base URL
 * ({@code "http://reports.resorts-internal.com:8080/download/"}) previously returned
 * by {@link #buildReportDownloadUrl(String)} has been replaced with a value retrieved
 * at runtime from AWS Systems Manager Parameter Store, enabling environment-agnostic
 * deployments without code changes.
 *
 * <p>cr-java-0077 FIX: The hard-coded {@code SERVER_PORT = 8080} constant (original
 * line 28) has been removed entirely.  The server port is now externalised to AWS
 * Systems Manager Parameter Store and injected at runtime through the {@code SERVER_PORT}
 * environment variable in ECS task definitions, EKS pod specs, or Elastic Beanstalk
 * environment properties.  The {@code server.port} Spring Boot property reads from
 * {@code ${SERVER_PORT:8080}} so the application starts on the correct port in every
 * environment without any code or build changes.
 *
 * <p>Required environment variables / application properties:
 * <ul>
 *   <li>{@code AWS_REPORTS_BUCKET}  — S3 bucket name for generated reports</li>
 *   <li>{@code AWS_REPORTS_PREFIX}  — optional key prefix (folder) inside the bucket</li>
 *   <li>{@code AWS_REGION}          — AWS region for the S3 client (default: us-east-1)</li>
 *   <li>{@code SERVER_PORT}         — server listening port injected by ECS/EKS/Beanstalk
 *                                     (default: 8080 for local development only)</li>
 *   <li>{@code ssm.parameter.report.download-base-url} — SSM parameter name for the
 *       report download base URL (default: /resortslite/reports/download-base-url)</li>
 *   <li>{@code app.report.download.url.default} — fallback URL for local development</li>
 * </ul>
 */
@Service
public class ReportService {

    // cr-java-0062 FIX: Replaced hard-coded absolute path "/var/legacy/reports/" with an
    // environment-variable-backed property.  The S3 bucket name is injected at runtime so
    // the application is portable across all cloud environments and containers.
    @Value("${aws.s3.reports.bucket:${AWS_REPORTS_BUCKET:resorts-lite-reports}}")
    private String reportsBucket;

    // cr-java-0062 FIX: Replaced hard-coded Windows path "C:\ResortBackups\nightly\" with
    // an S3 key prefix injected via environment variable, eliminating the OS-specific
    // path dependency entirely.
    @Value("${aws.s3.reports.prefix:${AWS_REPORTS_PREFIX:reports/}}")
    private String reportsPrefix;

    // cr-java-0071 FIX: SSM parameter name for the report download base URL, injected from
    // application.properties / environment variable so it can differ per environment.
    @Value("${ssm.parameter.report.download-base-url:/resortslite/reports/download-base-url}")
    private String reportDownloadUrlParamName;

    // cr-java-0071 FIX: Fallback URL used only when SSM is unavailable (e.g. local dev).
    @Value("${app.report.download.url.default:https://reports.resorts-internal.com/download/}")
    private String reportDownloadUrlDefault;

    // cr-java-0077 FIX: Replaced hard-coded "private static final int SERVER_PORT = 8080"
    // (original line 28) with a Spring @Value field that reads the SERVER_PORT environment
    // variable injected by ECS task definitions, EKS pod environment specs, or Elastic
    // Beanstalk environment properties at runtime.  The SSM parameter name for the port is
    // also externalised so it can be managed per environment in AWS Parameter Store without
    // any code changes.  The default value (8080) is used only for local development.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0077 FIX: SSM parameter name for the server port, allowing the port to be
    // managed centrally in AWS Systems Manager Parameter Store per deployment environment.
    @Value("${ssm.parameter.server.port:/resortslite/server/port}")
    private String serverPortParamName;

    private final S3Client s3Client;

    // cr-java-0071 FIX: Injected SSM client to resolve environment-specific URLs at runtime.
    @Autowired
    private SsmClient ssmClient;

    /**
     * Constructs a {@code ReportService} with an AWS SDK v2 {@link S3Client}.
     * The client automatically resolves credentials from the standard AWS credential
     * chain (IAM role, environment variables, ~/.aws/credentials) so no secrets are
     * hard-coded in the application.
     *
     * @param s3Client the AWS S3 client bean
     */
    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly booking report in CSV format and uploads it to Amazon S3.
     *
     * <p>cr-java-0062 FIX: Previously this method wrote the CSV to a local absolute path
     * using {@code new FileWriter(fullPath)} (original line 42), which is ephemeral in
     * cloud/container environments and causes permanent data loss on container restart.
     * The file is now stored as an S3 object under the configured bucket and prefix,
     * making it durable and accessible from any cloud instance.
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year  for which the report is generated (e.g. "2024")
     * @return a map containing the operation status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0062 FIX: Replaced local file path construction
        // (REPORT_BASE_PATH + fileName) with an S3 object key built from the
        // environment-variable-backed prefix.
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key    = reportsPrefix + fileName;   // e.g. "reports/resort_report_03_2024.csv"

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062 FIX (original lines 37–46): Replaced all local file-system write
            // operations:
            //   - new File(REPORT_BASE_PATH)          → removed (no local dir needed)
            //   - reportDir.mkdirs()                  → removed (S3 is a flat object store)
            //   - new FileWriter(fullPath)  [line 42] → replaced with S3 PutObjectRequest
            //   - writer.write(...)                   → replaced with RequestBody.fromString()
            //   - writer.close()                      → removed (SDK manages connection lifecycle)
            // Data is now written directly to Amazon S3, ensuring durability across container
            // restarts and horizontal scaling events.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            PutObjectResponse putResponse = s3Client.putObject(
                    putRequest,
                    RequestBody.fromString(csvContent)
            );

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", s3Key);
            result.put("eTag", putResponse.eTag());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name using the base URL retrieved
     * from AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0071 FIX: Replaced the hard-coded environment-specific URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} with a value
     * fetched at runtime from AWS SSM Parameter Store under the parameter name
     * configured via {@code ssm.parameter.report.download-base-url}
     * (default: {@code /resortslite/reports/download-base-url}).
     * This allows the base URL to differ across dev, staging, and production
     * environments without any code or build changes — only the SSM parameter
     * value needs to be updated per environment.
     *
     * @param reportName the report file name to append to the base URL
     * @return the fully qualified download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Replaced hard-coded URL
        //   "http://reports.resorts-internal.com:8080/download/" + reportName
        // with a base URL retrieved from AWS SSM Parameter Store at runtime.
        String baseUrl = AwsSsmConfig.getParameter(
                ssmClient,
                reportDownloadUrlParamName,
                reportDownloadUrlDefault);

        // Ensure the base URL ends with a slash before appending the report name.
        if (!baseUrl.endsWith("/")) {
            baseUrl = baseUrl + "/";
        }
        return baseUrl + reportName;
    }

    /**
     * Returns system information relevant to the report storage configuration.
     *
     * <p>cr-java-0077 FIX: Previously exposed the hard-coded {@code SERVER_PORT = 8080}
     * constant (original line 28) via {@code info.put("serverPort", SERVER_PORT)}.
     * The port value is now sourced from the {@code SERVER_PORT} environment variable
     * injected by ECS/EKS/Beanstalk at runtime, or from AWS SSM Parameter Store via
     * the {@code /resortslite/server/port} parameter, ensuring the reported port always
     * reflects the actual runtime configuration rather than a compile-time constant.
     *
     * <p>Previously also exposed hard-coded local paths; now returns the S3 bucket and
     * prefix so operators can verify the correct cloud storage target at runtime.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replaced java.util.Date + SimpleDateFormat (server-local timezone)
        // with java.time.Instant formatted via DateTimeFormatter in explicit UTC (ZoneOffset.UTC).
        // This eliminates timezone inconsistencies across distributed cloud containers/regions.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0062 FIX: Replaced REPORT_BASE_PATH and BACKUP_PATH constants
        // (hard-coded absolute file-system paths) with S3 bucket/prefix values
        // sourced from environment variables.
        info.put("s3ReportsBucket", reportsBucket);
        info.put("s3ReportsPrefix", reportsPrefix);
        // cr-java-0077 FIX: Replaced hard-coded SERVER_PORT constant with the runtime
        // value injected via SERVER_PORT environment variable / SSM Parameter Store.
        // The port is resolved from AWS SSM Parameter Store at runtime; the @Value-injected
        // serverPort field serves as the fallback when SSM is unavailable (local dev).
        String resolvedPortStr = AwsSsmConfig.getParameter(
                ssmClient,
                serverPortParamName,
                String.valueOf(serverPort));
        info.put("serverPort", resolvedPortStr);
        info.put("generatedAt", timestamp);
        return info;
    }
}
