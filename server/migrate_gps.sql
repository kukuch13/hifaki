-- Jalankan SATU KALI pada instalasi HIFAKI yang sudah memakai schema.sql lama.
-- Jangan jalankan kembali setelah kolom GPS tersedia.
ALTER TABLE hifaki_records
    ADD COLUMN latitude DECIMAL(10,7) NULL,
    ADD COLUMN longitude DECIMAL(10,7) NULL,
    ADD COLUMN gps_accuracy_m DECIMAL(8,1) NULL,
    ADD COLUMN gps_at BIGINT UNSIGNED NULL;
