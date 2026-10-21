# HIFAKI — skrining hipotermia di lapangan

Proyek Android Studio **Java/XML (antarmuka dibuat secara native di Java)** dengan alat **ESP32-C3 SuperMini**. Logo asli dari `HIFAKI.png` digunakan pada splash screen; lambang merahnya disesuaikan untuk ikon kecil aplikasi. Tangkapan layar awal menjadi acuan monitor, riwayat, dan splash, tetapi **tidak menunjukkan model sensor**, sehingga pilihan DS18B20/MAX30102/BMP280 tetap asumsi yang dapat diganti setelah perangkat keras asli diketahui.

## Isi dan cara kerja

| Fitur | Perilaku |
| --- | --- |
| Splash | Gambar logo HIFAKI asli, loading singkat lalu beranda. |
| Navigasi kembali | `OnBackPressedDispatcher` AndroidX: dari tab lain kembali ke beranda, dari beranda keluar mengikuti navigasi sistem. |
| Monitor offline | ESP32-C3 mengirim paket BLE 12 byte setiap 2 detik tanpa Wi-Fi atau sinyal seluler. |
| GPS offline | GNSS/GPS **ponsel** menampilkan lintang, bujur, waktu dan akurasi tanpa data seluler. Saat menyimpan pemeriksaan, hanya koordinat yang berumur ≤60 detik dan akurasinya ≤500 m yang dicatat. GPS aktif hanya saat layar Monitor terbuka. |
| Validasi | `-127 °C`, `85 °C` saat sensor belum siap, denyut tanpa jari, nilai tak masuk akal, dan data lebih dari 10 detik tidak dipakai. |
| Penilaian | Suhu DS18B20 diberi label **suhu kontak/permukaan**. Kategori ringan/sedang/berat hanya dihitung dari suhu inti yang dimasukkan petugas dengan verifikasi sumber pengukuran. Gejala bahaya memicu arahan darurat. |
| Riwayat | SQLite lokal, cari nama/kode, detail, hapus catatan dan salin koordinat GPS. Catatan lama tetap tersedia setelah pembaruan basis data. |
| Foto pemeriksaan | Ambil lewat kamera atau pilih dari galeri; foto dikecilkan menjadi JPEG, orientasi umum diperbaiki, metadata EXIF dibuang, lalu disimpan privat bersama catatan. Foto dapat dilihat pada detail riwayat dan dihapus bersama catatan. **Foto tidak diunggah ke server.** |
| Online | JobScheduler mengirim catatan tertunda (termasuk GPS bila tersedia) ke API PHP/MySQL melalui HTTPS ketika jaringan tersedia; tombol Sinkron sekarang juga tersedia. Penerimaan ulang aman berkat UUID unik. |
| Privasi | Database privat aplikasi, backup Android dimatikan, token API dienkripsi memakai Android Keystore, BLE meminta pairing dengan passkey unik pada alat. |

Alur: **alat → BLE → aplikasi dan SQLite ← GPS ponsel → HTTPS bila tersedia → MySQL**. ESP32 tidak memerlukan Wi-Fi. GPS memakai penerima satelit **di ponsel**, bukan modul pada ESP32-C3; ponsel harus memiliki GNSS dan pandangan langit yang memadai. Lokasi dari GPS tetap tersedia tanpa sinyal seluler setelah memperoleh fix. Server bersifat opsional dan hanya menerima catatan, belum menyediakan dashboard atau akun multi petugas.

## Buka di Android Studio

1. Pasang **Android Studio Quail 4 (2026.1.4)** atau versi stabil lebih baru, **JDK 17**, **Android SDK API 37** dan Build Tools 36.0.0.
2. Ekstrak ZIP, lalu **File → Open → pilih folder `HIFAKI`** (folder yang memuat `settings.gradle`). Tunggu sinkronisasi. AGP 9.4.0, Gradle 9.6.0, AndroidX Activity 1.13.0 dan Core 1.17.0 akan diunduh pada komputer Anda.
3. Jalankan di ponsel Android 8.0+ dengan Bluetooth LE. Emulator bisa dipakai untuk meninjau layar dan memasukkan suhu inti, tetapi pemindaian BLE perlu ponsel fisik.
4. Pada Windows, `gradlew.bat assembleDebug` menghasilkan `app/build/outputs/apk/debug/app-debug.apk` setelah SDK dan dependensi terpasang.

### Menggunakan GPS

1. Buka **Monitor → Mulai / perbarui GPS**, pilih **Lokasi presisi** saat Android meminta izin, dan aktifkan tombol Lokasi/GPS pada ponsel. Permintaan izin ditampilkan saat fitur digunakan, bukan saat splash screen.
2. Tunggu di tempat terbuka sampai lintang/bujur dan akurasi muncul. **Salin koordinat** menyalin pasangan `lintang, bujur` untuk komunikasi petugas; fitur ini tidak membutuhkan peta online.
3. Simpan pemeriksaan saat lokasi masih segar. Riwayat menampilkan koordinat bila tersedia, serta tombol **Salin GPS** pada detail. Jika GPS dimatikan, izin ditolak, tidak ada satelit, akurasi >500 m, atau lokasi berumur >60 detik, pemeriksaan **tetap dapat disimpan tanpa koordinat**.
4. Ketinggian di Monitor tetap estimasi dari BMP280 pada **alat**; GPS mengambil posisi lintang/bujur dari **ponsel**. Versi ini tidak memasang modul GPS eksternal pada ESP32, serta tidak mengunduh peta untuk dipakai offline.

### Foto pemeriksaan

Di Monitor, isi nama/kode lalu gunakan **Ambil foto kamera** atau **Pilih dari galeri**. Foto pilihan terlihat sebelum tombol **Simpan pemeriksaan offline** ditekan; foto bersifat opsional. Di Riwayat, buka detail catatan untuk melihatnya. Foto tinggal di penyimpanan privat aplikasi, tetap tersedia tanpa internet, dan dihapus saat catatan lokal dihapus. Foto tidak ikut dikirim oleh API online, sehingga server tidak menyimpan wajah orang yang diperiksa. Jangan hapus data aplikasi atau mencopot aplikasi sebelum data lokal yang dibutuhkan diamankan, karena foto lokal juga akan terhapus.

Proyek tidak menyertakan APK hasil build. Lingkungan pembuatan paket ini tidak memiliki Android SDK/Gradle, sehingga APK perlu dikompilasi di Android Studio. Berkas `gradlew` dan `gradlew.bat` merupakan bootstrap kecil yang mengunduh distribusi resmi Gradle 9.6.0 pertama kali; `gradle/wrapper/gradle-wrapper.properties` menyatakan versi yang sama.

## Komponen dan wiring contoh

Panduan lengkap berisi seluruh koneksi, opsi daya 18650, pemeriksaan Serial Monitor, dan langkah unggah ada di **[`firmware/WIRING.md`](firmware/WIRING.md)**. Kode alat siap dibuka dari **`firmware/HIFAKI_C3/HIFAKI_C3.ino`**.

**Papan yang diasumsikan: ESP32-C3 SuperMini 3,3 V.** Periksa label pin fisik papan karena klon dapat berbeda.

| Komponen | Pin sensor | ESP32-C3 | Catatan |
| --- | --- | --- | --- |
| DS18B20 | VDD, GND, DQ | 3V3, GND, GPIO3 | Resistor pull-up 4,7 kΩ antara DQ dan 3V3. Gunakan mode catu 3 kabel. |
| MAX30102 breakout | VCC, GND, SDA, SCL | sesuai breakout, GND, GPIO6, GPIO7 | **IC asli membutuhkan suplai inti 1,8 V dan LED 3,3 V**; hanya hubungkan 3V3 langsung jika modul breakout memiliki regulator/level shifting yang sesuai. |
| BMP280 breakout | VCC, GND, SDA, SCL | 3V3, GND, GPIO6, GPIO7 | Bus I²C bersama MAX30102; alamat dicoba `0x76` lalu `0x77`. |
| Baterai opsional | BATT+ → 100 kΩ → GPIO0 → 100 kΩ → GND | GPIO0 (ADC) | Set `HAS_BATTERY_DIVIDER=true` setelah rangkaian dipasang. Persentase hanya estimasi tegangan. |

Untuk 1×18650, gunakan sel berkualitas, rangkaian charger/proteksi yang cocok, dan **konverter tegangan teregulasi sesuai input papan**. Jangan hubungkan sel Li-ion 4,2 V langsung ke pin 3V3. Cek juga karakteristik power path bila alat harus bekerja sambil diisi. Kabel dan probe suhu harus aman untuk kontak kulit; probe waterproof serbaguna tidak otomatis layak untuk pengukuran suhu inti medis.

## Unggah firmware

1. Pada Arduino IDE terbaru, pasang board package **esp32 by Espressif**, pilih board **ESP32C3 Dev Module** untuk C3 SuperMini yang kompatibel (periksa varian papan dan USB CDC).
2. Instal pustaka: **OneWire**, **DallasTemperature**, **SparkFun MAX3010x Sensor Library**, **Adafruit BMP280 Library** (beserta dependensi Adafruit BusIO), dan **NimBLE-Arduino 2.1.0+**.
3. Buka `firmware/HIFAKI_C3/HIFAKI_C3.ino`, periksa pin dan `SEA_LEVEL_HPA`, unggah. ESP32 menggunakan BLE; tidak ada SSID atau kata sandi Wi-Fi.
4. Buka Serial Monitor 115200. Pada boot pertama firmware membuat **kode pairing unik 6 digit** dan menyimpannya. Catat kode, pasangkan ponsel di ruang persiapan, lalu simpan kode secara fisik untuk petugas. Jangan tampilkan serial di tempat umum. Jika flash/NVS dihapus, kode berubah dan pairing lama harus dihapus di Android.
5. Buka HIFAKI → Monitor → **Cari & hubungkan HIFAKI** → izinkan *Perangkat terdekat* → masukkan kode pada prompt Bluetooth. Pada Android 8–11, izin lokasi diperlukan untuk pemindaian BLE.

Firmware mencetak scan bus I²C saat boot (`MAX30102=0x57`, `BMP280=0x76/0x77`), sehingga kabel SDA/SCL dan suplai bisa diperiksa sebelum memasangkan ponsel. GPS dan kamera dijalankan oleh ponsel Android dan tidak memerlukan komponen tambahan pada alat.

Jika suhu sempat menampilkan `-127` di aplikasi lama, cek kabel DQ, resistor 4,7 kΩ, catu 3,3 V, konektor, dan GND bersama. HIFAKI baru akan menampilkan **—** ketika pembacaan gagal. Tekanan acuan `SEA_LEVEL_HPA=1013.25` hanya contoh; kalibrasikan agar angka m dpl bermakna. MAX30102 membutuhkan jari yang diam; saat tidak ada jari, denyut ditampilkan **—**.

## Mode online dengan hosting PHP/MySQL

1. Siapkan hosting dengan PHP **8.1+**, PDO MySQL, sertifikat TLS, dan database MySQL. Jalankan `server/schema.sql` melalui phpMyAdmin.
2. Unggah `server/api.php` ke `public_html/hifaki/api.php` pada domain **HTTPS**.
3. Salin `server/config.example.php` sebagai `~/private/config.php`, **di luar `public_html`**. Isi kredensial MySQL dan kunci acak minimal 32 karakter. Path pada `api.php` mengasumsikan tata letak ini.
4. Isi URL `https://domain-anda/hifaki/api.php` dan kunci API di Pengaturan aplikasi. Simpan satu catatan untuk uji; gunakan **Sinkron sekarang** dan pastikan status menjadi “Tersinkron”.

**Pembaruan server lama:** jika tabel `hifaki_records` sudah dibuat sebelum versi 1.1, jalankan `server/migrate_gps.sql` **satu kali** pada database yang sama, kemudian ganti `api.php` dengan versi ini **sebelum** melakukan sinkronisasi catatan GPS. Untuk pemasangan baru, cukup gunakan `server/schema.sql`. API lama akan mengabaikan kolom GPS sehingga catatan dapat ditandai tersinkron tanpa lokasinya tersimpan di server.

Server sengaja hanya menerima `POST`. Tidak ada halaman untuk membaca nama pasien melalui internet. Gunakan kode pasien alih-alih nama lengkap bila memungkinkan, batasi siapa yang memegang kunci, dan terapkan kebijakan akses, enkripsi penyimpanan, persetujuan, serta retensi data sebelum penggunaan nyata.
**Koordinat GPS terkait pasien adalah data sensitif.** Beri tahu petugas bahwa lokasi akan ikut dikirim melalui HTTPS saat mode online disetel; jangan mengaktifkan sinkronisasi ke server yang tidak dipercaya. Fitur GPS hanya meminta izin saat dipakai di layar Monitor dan berhenti ketika aplikasi ditinggalkan.
**Foto orang yang diperiksa juga sensitif.** Aplikasi menyalin foto pilihan ke penyimpanan privat, membuang metadata aslinya, dan tidak mengirim berkas foto ke API. Batasi akses ponsel dan dapatkan izin subjek sesuai prosedur setempat.

## Keterbatasan klinis dan teknis

- **Prototipe skrining, bukan alat diagnosis medis.** Hipotermia didefinisikan berdasarkan **suhu inti <35 °C**. Sensor kontak DS18B20 tidak mengukur suhu inti dan tidak boleh dipakai untuk mengklasifikasi pasien. Fitur input suhu inti mengandaikan termometer medis yang memang mampu membaca rentang suhu rendah dan telah divalidasi.
- Kategori suhu inti yang dipakai: `32–<35 °C` ringan, `28–<32 °C` sedang, `<28 °C` berat. Gejala berat dapat muncul tanpa angka sensor; ikuti penilaian klinis dan rujuk segera bila dicurigai hipotermia.
- Algoritma nadi MAX30102 dan tinggi BMP280 adalah indikatif; verifikasi dengan alat yang sesuai. Peringatan darurat tidak bergantung pada data nadi maupun ketinggian.
- JobScheduler bekerja mengikuti kebijakan sistem; sinkronisasi dapat tertunda setelah internet kembali. Tombol sinkron manual tersedia. Jika catatan lokal yang sudah tersinkron dihapus, salinan di server tetap ada.
- Pemantauan BLE langsung berjalan saat aplikasi terbuka. Versi ini belum memiliki layanan pemantauan kontinu saat aplikasi ditutup atau layar dimatikan.
- GPS ponsel tidak menjamin fix di dalam bangunan/lembah/hutan lebat, dan akurasi yang ditampilkan hanyalah perkiraan. Lokasi terakhir yang kedaluwarsa tidak dipakai untuk catatan baru. Penerima GNSS ESP32 eksternal memerlukan modul dan firmware lain jika alat harus mengetahui lokasinya tanpa ponsel.
- Belum diuji pada papan ESP32, sensor, dan ponsel fisik dalam sesi ini. Kalibrasi sensor, uji kestabilan BLE, uji daya tahan baterai, dan evaluasi klinis wajib dilakukan sebelum pemakaian lapangan.

## Pemeriksaan yang bisa dijalankan tanpa Android SDK

```bash
mkdir -p /tmp/hifaki-test
javac -d /tmp/hifaki-test app/src/main/java/id/hifaki/app/{Reading,Assessment}.java tools/ProtocolTest.java
java -cp /tmp/hifaki-test id.hifaki.app.ProtocolTest
php -l server/api.php
```

## Rujukan teknis

- [Android Studio Quail 4 dan kompatibilitas AGP](https://developer.android.com/studio/releases) serta [AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes).
- [Izin Bluetooth Android](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [pemindaian BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices), [JobScheduler](https://developer.android.com/reference/android/app/job/JobInfo.Builder).
- [Izin lokasi presisi Android](https://developer.android.com/develop/sensors-and-location/location/permissions), [LocationManager GPS_PROVIDER](https://developer.android.com/reference/android/location/LocationManager), [baterai dan siklus hidup lokasi](https://developer.android.com/develop/sensors-and-location/location/battery/optimize).
- [TakePicture](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.TakePicture), [OpenDocument](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument), [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider), [penyimpanan privat aplikasi](https://developer.android.com/training/data-storage/app-specific).
- [Datasheet DS18B20](https://www.analog.com/media/en/technical-documentation/data-sheets/DS18B20.pdf), [datasheet MAX30102](https://www.analog.com/media/en/technical-documentation/data-sheets/max30102.pdf), [datasheet BMP280](https://www.bosch-sensortec.com/media/boschsensortec/downloads/datasheets/bst-bmp280-ds001.pdf).
- [CDC: hipotermia dan tindakan awal](https://wwwnc.cdc.gov/travel/page/travel-to-cold-climates); [CDC Yellow Book: tahapan hipotermia](https://www.cdc.gov/yellow-book/hcp/environmental-hazards-risks/heat-and-cold-illness-in-travelers.html).
- [NimBLE-Arduino 2.x migration](https://github.com/h2zero/NimBLE-Arduino/blob/master/docs/1.x_to2.x_migration_guide.md) dan [SparkFun MAX301x](https://github.com/sparkfun/SparkFun_MAX3010x_Sensor_Library).
