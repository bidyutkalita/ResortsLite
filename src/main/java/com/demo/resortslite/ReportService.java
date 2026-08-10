package com.demo.resortslite;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Blocker-1,2,3,4,5,6,7: Hard-coded file paths and java.io.File usage replaced with
    // Azure Blob Storage configuration loaded from environment variables.
    @Value("${AZURE_STORAGE_CONNECTION_STRING:#{null}}")
    private String storageConnectionString;

    // Blocker-12: Hard-coded port replaced with environment variable / Azure App Configuration.
    @Value("${SERVER_PORT:${server.port:8080}}")
    private int serverPort;

    // Blocker-11: Hard-coded environment URL replaced with Azure App Configuration value.
    @Value("${REPORT_DOWNLOAD_BASE_URL:${app.report.download.base-url:https://reports.resorts-internal.com/download}}")
    private String reportDownloadBaseUrl;

    // Blocker-19: Azure Service Bus connection string for scheduled message delivery.
    @Value("${AZURE_SERVICE_BUS_CONNECTION_STRING:#{null}}")
    private String serviceBusConnectionString;

    @Value("${AZURE_SERVICE_BUS_QUEUE_NAME:report-scheduler-queue}")
    private String serviceBusQueueName;

    @Value("${AZURE_STORAGE_CONTAINER_NAME:resort-reports}")
    private String containerName;

    /**
     * Generates a monthly report and uploads it to Azure Blob Storage.
     * Replaces all local java.io.File / FileWriter operations (blockers 1-7).
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            byte[] contentBytes = csvContent.getBytes(StandardCharsets.UTF_8);
            InputStream inputStream = new ByteArrayInputStream(contentBytes);

            // Upload to Azure Blob Storage (blocker-1,2,3,4,5,6,7)
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .connectionString(storageConnectionString)
                    .buildClient();

            BlobContainerClient containerClient = blobServiceClient
                    .getBlobContainerClient(containerName);
            if (!containerClient.exists()) {
                containerClient.create();
            }

            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.upload(inputStream, contentBytes.length, true);

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("containerName", containerName);
            // Blocker-12: serverPort sourced from environment variable, not hard-coded
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using externalized base URL from Azure App Configuration.
     * Blocker-11: Hard-coded environment URL replaced with injected configuration value.
     */
    public String buildReportDownloadUrl(String reportName) {
        // reportDownloadBaseUrl is loaded from Azure App Configuration / environment variable
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information using externalized configuration values.
     * Blocker-12: serverPort sourced from environment variable.
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("storageContainer", containerName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Schedules a report generation task via Azure Service Bus scheduled messages.
     * Blocker-19: Replaces java.util.Timer with Azure Service Bus for distributed,
     * timezone-agnostic task execution.
     */
    public Map<String, Object> scheduleReportGeneration(String month, String year, long delaySeconds) {
        Map<String, Object> result = new HashMap<>();
        try {
            ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                    .connectionString(serviceBusConnectionString)
                    .sender()
                    .queueName(serviceBusQueueName)
                    .buildClient();

            String messageBody = "{\"action\":\"generateReport\",\"month\":\"" + month
                    + "\",\"year\":\"" + year + "\"}";

            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            // Schedule the message for future delivery (distributed, timezone-agnostic)
            java.time.OffsetDateTime scheduledTime = java.time.OffsetDateTime.now()
                    .plusSeconds(delaySeconds);
            long sequenceNumber = senderClient.scheduleMessage(message, scheduledTime);

            senderClient.close();

            result.put("status", "scheduled");
            result.put("sequenceNumber", sequenceNumber);
            result.put("scheduledAt", scheduledTime.toString());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }
}
