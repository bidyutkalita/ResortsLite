-- PostgreSQL 16 schema initialisation for ResortsLite
-- Replaces the H2 in-memory schema used during development.
-- This script is executed automatically by Spring Boot on startup
-- when spring.sql.init.mode=always is set in application.properties.

-- bookings table — stores resort reservation records
CREATE TABLE IF NOT EXISTS bookings (
    id        VARCHAR(20)  PRIMARY KEY,
    guest     VARCHAR(255) NOT NULL,
    room      VARCHAR(50)  NOT NULL,
    checkin   VARCHAR(20)  NOT NULL,
    checkout  VARCHAR(20)  NOT NULL
);
