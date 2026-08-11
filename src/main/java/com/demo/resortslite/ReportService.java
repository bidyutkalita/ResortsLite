package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // blocker-2, blocker-5 (cz-java-0057): Replaced hardcoded absolute path /var/legacy/reports/
    // with environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap on EKS.
    private static final String REPORT_BASE_PATH = System.getenv("REPORT_BASE_PATH") != null
            ? System.getenv("REPORT_BASE_PATH")
            : "/reports";

    // blocker-3, blocker-6 (cz-java-0057): Replaced hardcoded Windows-style absolute path
    // C:\ResortBackups\nightly\ with environment variable BACKUP_PATH injected via Kubernetes ConfigMap.
    private static final String BACKUP_PATH = System.getenv("BACKUP_PATH") != null
            ? System.getenv("BACKUP_PATH")
            : "/backups";

    // blocker-21, blocker-22 (cz-java-0061): Replaced hardcoded port 8080 with environment
    // variable SERVER_PORT injected via Kubernetes ConfigMap for flexible container port binding.
    private static final int SERVER_PORT = System.getenv("SERVER_PORT") != null
            ? Integer.parseInt(System.getenv("SERVER_PORT"))
            : 8080;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + "/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            FileWriter writer = new FileWriter(fullPath);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.close();

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", SERVER_PORT);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
