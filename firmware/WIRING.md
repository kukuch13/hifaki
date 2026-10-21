# Wiring dan firmware HIFAKI — ESP32-C3 SuperMini

Dokumen ini cocok dengan `HIFAKI_C3/HIFAKI_C3.ino`. Pin yang dipilih adalah **nomor GPIO**, bukan urutan kaki fisik. Periksa tulisan pada papan ESP32-C3 SuperMini Anda karena tata letak beberapa klon berbeda. Semua sambungan sensor memakai **GND bersama** dan logika **3,3 V**.

## Komponen

- 1 × ESP32-C3 SuperMini dengan BLE, kabel USB data, dan sumber daya USB 5 V.
- 1 × sensor DS18B20 **mode tiga kabel**, 1 × resistor **4,7 kΩ**.
- 1 × **modul breakout MAX30102** yang secara jelas mendukung suplai/input 3,3 V dan level I²C 3,3 V. Jangan memakai IC MAX30102 telanjang langsung ke pin 3V3.
- 1 × modul breakout BMP280 I²C dengan logika 3,3 V.
- Kabel pendek, konektor yang terlindung, casing, dan probe yang aman untuk kontak kulit.

## Sambungan yang harus dibuat

| Kabel | Dari modul | Ke ESP32-C3 SuperMini | Keterangan |
| --- | --- | --- | --- |
| Merah | DS18B20 VDD | `3V3` | Suplai tiga kabel, **bukan** mode parasit. |
| Hitam | DS18B20 GND | `GND` | GND bersama. |
| Kuning | DS18B20 DQ / DATA | `GPIO3` | Tambah resistor 4,7 kΩ dari jalur **DQ ke 3V3**. |
| Merah | MAX30102 breakout VIN / 3V3 | `3V3` | **Hanya** jika spesifikasi breakout mengizinkan 3,3 V; beberapa modul perlu regulator dan level shifter. |
| Hitam | MAX30102 GND | `GND` | GND bersama. |
| Biru | MAX30102 SDA | `GPIO6` | Bus I²C bersama BMP280; alamat umum `0x57`. |
| Ungu | MAX30102 SCL | `GPIO7` | Bus I²C bersama BMP280. Pin INT tidak dipakai. |
| Merah | BMP280 breakout VCC / VIN | `3V3` | Periksa pinout breakout, bukan asumsi dari warna kabel. |
| Hitam | BMP280 GND | `GND` | GND bersama. |
| Biru | BMP280 SDA | `GPIO6` | Gabungkan dengan SDA MAX30102. |
| Ungu | BMP280 SCL | `GPIO7` | Gabungkan dengan SCL MAX30102. |

Jika breakout BMP280 menyediakan **CSB/CS**, atur HIGH untuk I²C sesuai dokumentasi modul. **SDO** ke GND biasanya memberi alamat `0x76`, ke 3V3 memberi `0x77`; firmware mencoba keduanya. Pada modul empat pin, pengaturan ini umumnya sudah dibuat pada PCB. Jalur SDA/SCL mungkin sudah memiliki resistor pull-up di breakout; jangan menambah banyak pull-up paralel sebelum memeriksa rangkaian modul.

DS18B20, MAX30102, dan BMP280 tidak terhubung ke pin `GPIO2`, `GPIO8`, atau `GPIO9` yang dipakai sebagai *strapping pins* ESP32-C3. GPS dan kamera **berada di ponsel Android**, bukan pada rangkaian alat ini.

## Sumber daya untuk prototipe ringkas

**Paling sederhana:** gunakan power bank 5 V → kabel USB → port USB-C ESP32-C3. Semua sensor mendapat 3V3 dari papan bila total arus memenuhi kemampuan regulator papannya. Periksa spesifikasi breakout dan regulator klon sebelum memakai semua modul bersamaan.

**Alternatif 1 × 18650:** sel Li-ion → modul charger **dengan proteksi** → konverter boost 5 V yang teregulasi → konektor/kabel USB 5 V ke papan. Pilih modul dengan *power path* jika ingin alat tetap menyala saat diisi. Jangan sambungkan tegangan sel 4,2 V langsung ke pin `3V3`, atau menghubungkan keluaran 5 V dan USB komputer sekaligus tanpa memahami jalur daya papan. Pasang sakelar pada jalur yang sesuai dan lindungi semua sambungan baterai dari hubungan pendek.

**Pemantauan baterai opsional:** baterai `+` → resistor 100 kΩ → titik tengah **GPIO0** → resistor 100 kΩ → GND baterai. Satukan GND baterai dengan GND ESP32. Aktifkan `HAS_BATTERY_DIVIDER = true` dalam firmware **hanya sesudah** pembagi tegangan dipasang. Dengan sel penuh 4,2 V, GPIO0 menerima sekitar 2,1 V. Nilai persen dalam aplikasi adalah perkiraan tegangan, bukan kapasitas yang terkalibrasi. Jika memakai power bank biasa, biarkan opsi ini `false`.

## Memasang kode alat

1. Arduino IDE 2.x → Boards Manager → pasang **esp32 by Espressif Systems** (seri 3.x); pilih **ESP32C3 Dev Module** atau profil yang cocok dengan papan Anda. Siapkan pengaturan USB CDC/port sesuai varian papan.
2. Library Manager: pasang **OneWire**, **DallasTemperature**, **SparkFun MAX3010x Pulse and Proximity Sensor Library** (`MAX30105.h`, untuk MAX30102), **Adafruit BMP280 Library** beserta **Adafruit BusIO**, dan **NimBLE-Arduino 2.x**.
3. Buka `firmware/HIFAKI_C3/HIFAKI_C3.ino`. Periksa `PIN_TEMP=3`, `PIN_SDA=6`, `PIN_SCL=7`, dan sesuaikan `SEA_LEVEL_HPA` dengan tekanan acuan setempat untuk estimasi ketinggian.
4. Unggah ke ESP32-C3; buka Serial Monitor **115200 baud** dan tekan reset sekali jika pesan boot terlewat. Scan I²C idealnya menunjukkan `0x57` dan `0x76` atau `0x77`. Firmware juga mencetak status DS18B20 serta kode pairing BLE enam digit unik.
5. Pada Android: **Monitor → Cari & hubungkan HIFAKI**, beri izin Bluetooth dan masukkan kode pairing yang tercetak di serial. Setelah dipasangkan, paket BLE 12 byte dikirim setiap sekitar dua detik. Uji suhu kontak, jari pada MAX30102, dan nilai ketinggian; nilai yang gagal terbaca tampil sebagai `—`.

Jika `I2C scan: none`, periksa 3V3, GND, SDA, dan SCL terlebih dahulu. Jika DS18B20 menampilkan `—`, cek urutan kaki pada model sensor yang dipakai, resistor 4,7 kΩ, dan konektor. Jika denyut tetap `—`, tempatkan jari dengan stabil pada sensor serta jauhkan cahaya kuat; angka nadi ini bersifat indikatif. Suhu kontak kulit **bukan suhu inti** dan bukan dasar diagnosis.

## Rujukan

- [Pin dan *strapping pins* ESP32-C3 — Espressif](https://docs.espressif.com/projects/esp-hardware-design-guidelines/en/latest/esp32c3/schematic-checklist.html)
- [DS18B20 dan resistor pull-up — Analog Devices](https://support.analog.com/en-US/knowledgebase/article/000094969)
- [Library MAX3010x — SparkFun](https://github.com/sparkfun/SparkFun_MAX3010x_Sensor_Library) serta [tegangan IC MAX30102 pada breakout — SparkFun](https://learn.sparkfun.com/tutorials/sparkfun-gatorparticle-hookup-guide/hardware-overview)
- [BMP280 — Bosch Sensortec](https://www.bosch-sensortec.com/products/environmental-sensors/pressure-sensors/bmp280/)
- [NimBLE-Arduino 2.x — h2zero](https://github.com/h2zero/NimBLE-Arduino/blob/master/docs/1.x_to2.x_migration_guide.md)
