CREATE TABLE IF NOT EXISTS hifaki_records (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    measured_at BIGINT UNSIGNED NOT NULL,
    patient_name VARCHAR(80) NOT NULL,
    skin_c DECIMAL(5,2) NULL,
    bpm SMALLINT UNSIGNED NULL,
    altitude_m SMALLINT NULL,
    latitude DECIMAL(10,7) NULL,
    longitude DECIMAL(10,7) NULL,
    gps_accuracy_m DECIMAL(8,1) NULL,
    gps_at BIGINT UNSIGNED NULL,
    core_c DECIMAL(5,2) NULL,
    danger TINYINT(1) NOT NULL DEFAULT 0,
    assessment VARCHAR(100) NOT NULL,
    source VARCHAR(32) NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX ix_measured_at (measured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
