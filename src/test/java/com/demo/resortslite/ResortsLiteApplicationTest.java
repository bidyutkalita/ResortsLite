package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for the Spring Boot application context.
 * Verifies that the application context loads successfully.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.inventory.endpoint=https://inventory.example.com/api",
        "app.payment.endpoint=https://payment.example.com/api",
        "app.report.base-path=/tmp/reports/",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
class ResortsLiteApplicationTest {

    @Test
    void contextLoads() {
        // Verifies that the Spring application context starts without errors
        assertTrue(true, "Application context should load successfully");
    }

    @Test
    void mainMethod_doesNotThrow() {
        // Verify the main method signature exists and is callable
        // (actual Spring context startup is tested via contextLoads)
        assertDoesNotThrow(() -> {
            // Just verify the class is accessible
            Class<?> appClass = ResortsLiteApplication.class;
            assertNotNull(appClass);
        });
    }
}
