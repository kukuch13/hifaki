package id.hifaki.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URL;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

public final class MainActivity extends ComponentActivity implements BleClient.Listener {
    private static final int NEARBY_REQUEST = 71;
    private static final int GPS_REQUEST = 72;
    private static final Locale ID = Locale.forLanguageTag("id-ID");
    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { if (page == 1) updateMonitor(); tick.postDelayed(this, 2000); }
    };
    private Store store;
    private BleClient ble;
    private GpsTracker gps;
    private PhotoStore photos;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String[]> galleryLauncher;
    private File draftPhoto, captureFile;
    private boolean photoProcessing;
    private FrameLayout content;
    private LinearLayout nav;
    private int page;
    private Reading current;
    private Float verifiedCore;
    private boolean danger;
    private String bleStatus = "Belum terhubung • data bisa dicatat offline";
    private boolean warmerOn;
    private TextView warmerStatusText;
    private Button warmerButton;
    private TextView connectionText, skinText, heartText, spo2Text, altitudeText, freshnessText, assessmentText;
    private TextView gpsCoordinates, gpsStatus;
    private ImageView photoPreview;
    private TextView photoNote;
    private EditText nameInput;
    private CheckBox symptomBox;
    private OnBackPressedCallback backToHome;
    private DatabaseReference mDatabase;
    private ValueEventListener recordsListener;
    private ValueEventListener liveSensorListener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String dbUrl = "https://hifaki-100403-default-rtdb.asia-southeast1.firebasedatabase.app";
        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance(dbUrl);
            database.setPersistenceEnabled(true);
            mDatabase = database.getReference();
        } catch (Exception e) {
            try {
                mDatabase = FirebaseDatabase.getInstance(dbUrl).getReference();
            } catch (Exception ignored) {}
        }
        backToHome = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { go(0); }
        };
        getOnBackPressedDispatcher().addCallback(this, backToHome);
        store = new Store(this);
        ble = new BleClient(this, this);
        gps = new GpsTracker(this, this::updateGpsUi);
        photos = new PhotoStore(this);
        if (savedInstanceState != null) {
            String pendingCapture = savedInstanceState.getString("pending_capture");
            String pendingPhoto = savedInstanceState.getString("pending_photo");
            if (pendingCapture != null) captureFile = new File(pendingCapture);
            if (pendingPhoto != null) draftPhoto = new File(pendingPhoto);
        }
        cameraLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), saved -> {
            File captured = captureFile;
            captureFile = null;
            if (saved && captured != null) processPhoto(photos.captureUri(captured), captured);
            else { photos.deleteDraft(captured); if (saved) toast("Foto kamera tidak tersedia; coba lagi."); }
        });
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) processPhoto(uri, null);
        });
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 35) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 22), Ui.dp(this, 12));
        header.setBackgroundColor(Ui.BG);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_hifaki);
        header.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        LinearLayout heading = Ui.column(this);
        heading.setPadding(Ui.dp(this, 8), 0, 0, 0);
        heading.addView(Ui.text(this, "HIFAKI", 20, true, 0xffaa0000));
        heading.addView(Ui.text(this, "Hypothermia First Aid Kit", 12, false, Ui.MUTED));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView contour = new ImageView(this);
        contour.setImageResource(R.drawable.ic_mountain_contour);
        contour.setScaleType(ImageView.ScaleType.FIT_END);
        header.addView(contour, new LinearLayout.LayoutParams(Ui.dp(this, 100), Ui.dp(this, 42)));
        root.addView(header);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        nav = new LinearLayout(this);
        nav.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        nav.setBackgroundColor(Color.WHITE);
        root.addView(nav);
        setContentView(root);
        root.requestApplyInsets();
        render();
        tick.post(refresh);
        SyncJob.schedule(this);
    }

    @Override protected void onDestroy() {
        tick.removeCallbacks(refresh);
        gps.stop();
        ble.close();
        store.close();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (captureFile != null) out.putString("pending_capture", captureFile.getAbsolutePath());
        if (draftPhoto != null) out.putString("pending_photo", draftPhoto.getAbsolutePath());
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume() {
        super.onResume();
        if (content != null && page == 2) render();
        if (gps != null && page == 1 && gps.hasPrecisePermission()) gps.start();
        syncPendingRecordsToFirebase();
        listenForFirebaseRecords();
        listenForFirebaseLiveSensor();
        SyncJob.schedule(this);
    }

    @Override protected void onPause() {
        if (gps != null) gps.stop();
        super.onPause();
    }

    @Override public void onStatus(String status) {
        bleStatus = status;
        if (connectionText != null && page == 1) connectionText.setText(status);
        pushLiveSensorToFirebase(current);
    }

    @Override public void onReading(Reading reading) {
        current = reading;
        if (page == 1) updateMonitor();
        pushLiveSensorToFirebase(reading);
    }

    private void go(int tab) {
        if (page == 1 && tab != 1) gps.stop();
        page = tab;
        backToHome.setEnabled(tab != 0);
        render();
    }

    private void render() {
        connectionText = skinText = heartText = spo2Text = altitudeText = freshnessText = assessmentText = null;
        warmerStatusText = null;
        warmerButton = null;
        gpsCoordinates = gpsStatus = null;
        photoPreview = null;
        photoNote = null;
        nameInput = null;
        symptomBox = null;
        content.removeAllViews();
        nav.removeAllViews();
        String[] labels = {"⌂  Beranda", "◉  Monitor", "▤  Riwayat", "⚙  Pengaturan"};
        for (int i = 0; i < labels.length; i++) {
            final int tab = i;
            TextView item = Ui.text(this, labels[i], 12, true, i == page ? Ui.TEAL : Ui.MUTED);
            item.setGravity(Gravity.CENTER);
            item.setMinHeight(Ui.dp(this, 46));
            item.setBackground(Ui.bg(this, i == page ? 0xffe4f4f3 : 0xffffffff, 13));
            item.setOnClickListener(v -> go(tab));
            nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        switch (page) {
            case 1: monitor(); break;
            case 2: history(); break;
            case 3: settings(); break;
            default: home();
        }
    }

    private LinearLayout scrollPage() {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 28));
        scroller.addView(body);
        content.addView(scroller);
        return body;
    }

    private void section(LinearLayout parent, String title, String subtitle) {
        Ui.add(parent, Ui.text(this, title, 25, true, Ui.NAVY), 4);
        Ui.add(parent, Ui.text(this, subtitle, 14, false, Ui.MUTED), 18);
    }

    private void home() {
        LinearLayout body = scrollPage();
        LinearLayout hero = Ui.card(this);
        hero.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Ui.NAVY, 0xff167b85}));
        ((GradientDrawable) hero.getBackground()).setCornerRadius(Ui.dp(this, 20));
        hero.setPadding(Ui.dp(this, 22), Ui.dp(this, 26), Ui.dp(this, 22), Ui.dp(this, 26));
        Ui.add(hero, Ui.text(this, "Siap di lokasi,\nmeski tanpa sinyal.", 28, true, 0xffffffff), 12);
        Ui.add(hero, Ui.text(this, "ESP32-C3 → BLE → ponsel. GPS ponsel mencatat lokasi tanpa internet; kirim catatan saat koneksi kembali.", 15, false, 0xffe4f9f7), 22);
        Button begin = Ui.button(this, "Mulai pemantauan", Ui.RED);
        begin.setOnClickListener(v -> go(1));
        Ui.add(hero, begin, 0);
        Ui.add(body, hero, 18);

        LinearLayout safety = Ui.card(this);
        safety.setBackground(Ui.bg(this, 0xfffff0f1, 20));
        Ui.add(safety, Ui.text(this, "Tanda bahaya", 19, true, Ui.RED), 8);
        Ui.add(safety, Ui.text(this, "Bingung, bicara cadel, sangat mengantuk, pingsan, atau mati rasa. Segera cari pertolongan medis.", 15, false, Ui.NAVY), 10);
        Ui.add(safety, Ui.text(this, "Pindahkan dari dingin, lepaskan pakaian basah, selimuti, dan hangatkan bagian tengah tubuh secara bertahap.", 14, false, Ui.MUTED), 0);
        Ui.add(body, safety, 18);

        LinearLayout ref = Ui.card(this);
        Ui.add(ref, Ui.text(this, "Referensi Medis", 19, true, Ui.NAVY), 12);
        Ui.add(ref, Ui.text(this, "SUHU INTI TUBUH", 12, true, Ui.TEAL), 6);
        Ui.add(ref, Ui.text(this, "• Normal: 36,5 – 37,5 °C\n• Hipotermia Ringan: 32 – 35 °C\n• Hipotermia Sedang: 28 – 32 °C\n• Hipotermia Berat: < 28 °C", 14, false, Ui.NAVY), 12);
        Ui.add(ref, Ui.text(this, "DENYUT JANTUNG NORMAL (NADI)", 12, true, Ui.TEAL), 6);
        Ui.add(ref, Ui.text(this, "• Dewasa: 60 – 100 bpm\n• Anak (1–10 thn): 70 – 120 bpm\n• Bayi (<1 thn): 100 – 160 bpm", 14, false, Ui.NAVY), 8);
        Ui.add(ref, Ui.text(this, "Catatan: Pada kasus hipotermia, tubuh akan merespons dengan menurunkan laju denyut jantung (bradikardia).", 13, false, Ui.MUTED), 10);
        Ui.add(ref, Ui.text(this, "Sumber: World Health Organization (WHO) & Panduan Medis Klinis", 11, false, Ui.MUTED), 0);
        Ui.add(body, ref, 18);

        LinearLayout steps = Ui.card(this);
        Ui.add(steps, Ui.text(this, "Cara kerja HIFAKI", 19, true, Ui.NAVY), 12);
        Ui.add(steps, Ui.text(this, "01  Hubungkan alat lewat Bluetooth\n02  Periksa sensor dan aktifkan GPS bila diperlukan\n03  Simpan catatan di ponsel\n04  Sinkron otomatis bila internet tersedia", 15, false, Ui.NAVY), 14);
        Ui.add(steps, Ui.text(this, "Sensor kontak DS18B20 mengukur permukaan, bukan suhu inti. Kategori hipotermia memerlukan hasil suhu inti terverifikasi.", 14, false, Ui.TEAL), 0);
        Ui.add(body, steps, 0);
    }

    private TextView metric(LinearLayout body, String title, String initial, String suffix) {
        LinearLayout box = Ui.card(this);
        Ui.add(box, Ui.text(this, title, 14, true, Ui.MUTED), 6);
        TextView value = Ui.text(this, initial, 28, true, Ui.NAVY);
        Ui.add(box, value, 2);
        Ui.add(box, Ui.text(this, suffix, 12, false, Ui.MUTED), 0);
        Ui.add(body, box, 10);
        return value;
    }

    private void monitor() {
        LinearLayout body = scrollPage();
        section(body, "Monitor", "Sambungan lokal tetap bekerja tanpa internet.");
        LinearLayout conn = Ui.card(this);
        Ui.add(conn, Ui.text(this, "KONEKSI ALAT", 12, true, Ui.TEAL), 8);
        connectionText = Ui.text(this, bleStatus, 16, true, Ui.NAVY);
        Ui.add(conn, connectionText, 12);
        
        LinearLayout bleBtns = new LinearLayout(this);
        bleBtns.setOrientation(LinearLayout.HORIZONTAL);
        
        Button scan = Ui.button(this, "Mode Offline (BLE)", Ui.TEAL);
        scan.setOnClickListener(v -> startBle());
        bleBtns.addView(scan, new LinearLayout.LayoutParams(0, -2, 1));
        
        View gap = new View(this);
        bleBtns.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 10), 0));
        
        Button disconnect = Ui.button(this, "Mode Online", Ui.MUTED);
        disconnect.setOnClickListener(v -> {
            if (ble != null) ble.disconnect();
            toast("Mode Online aktif. Mendengarkan data dari Firebase.");
        });
        bleBtns.addView(disconnect, new LinearLayout.LayoutParams(0, -2, 1));
        
        Ui.add(conn, bleBtns, 0);
        Ui.add(body, conn, 12);

        skinText = metric(body, "SUHU KONTAK / PERMUKAAN", "— °C", "Bukan suhu inti; tidak untuk diagnosis hipotermia");
        heartText = metric(body, "DENYUT JANTUNG", "— bpm", "Letakkan jari pada MAX30102 hingga pembacaan stabil");
        spo2Text = metric(body, "SATURASI OKSIGEN (SpO2)", "— %", "Kadar oksigen dalam darah (MAX30102)");
        altitudeText = metric(body, "KETINGGIAN ESTIMASI", "— m dpl", "Dihitung dari tekanan BMP280 dan tekanan acuan");

        LinearLayout warmerCard = Ui.card(this);
        Ui.add(warmerCard, Ui.text(this, "RELAY / JAKET PENGHANGAT", 12, true, Ui.TEAL), 8);
        warmerStatusText = Ui.text(this, warmerOn ? "Pemanas Jaket: AKTIF (ON)" : "Pemanas Jaket: MATI (OFF)", 16, true, warmerOn ? Ui.RED : Ui.NAVY);
        Ui.add(warmerCard, warmerStatusText, 10);
        warmerButton = Ui.button(this, warmerOn ? "Matikan Jaket Penghangat (OFF)" : "Nyalakan Jaket Penghangat (ON)", warmerOn ? Ui.RED : Ui.TEAL);
        warmerButton.setOnClickListener(v -> toggleWarmerState());
        Ui.add(warmerCard, warmerButton, 8);
        Ui.add(warmerCard, Ui.text(this, "Kontrol relay elemen pemanas (heater PWM / Relay) pada jaket penghangat via Firebase & BLE.", 12, false, Ui.MUTED), 0);
        Ui.add(body, warmerCard, 12);

        LinearLayout location = Ui.card(this);
        Ui.add(location, Ui.text(this, "GPS PONSEL • TANPA INTERNET", 14, true, Ui.TEAL), 7);
        gpsCoordinates = Ui.text(this, "—", 22, true, Ui.NAVY);
        Ui.add(location, gpsCoordinates, 6);
        gpsStatus = Ui.text(this, gps.status(), 13, false, Ui.MUTED);
        Ui.add(location, gpsStatus, 10);
        Button startGps = Ui.button(this, "Mulai / perbarui GPS", Ui.TEAL);
        startGps.setOnClickListener(v -> startGpsFlow());
        Ui.add(location, startGps, 8);
        Button copyGps = Ui.button(this, "Salin koordinat", Ui.NAVY);
        copyGps.setOnClickListener(v -> {
            Location fix = gps.freshFix();
            if (fix == null) { toast("Tunggu koordinat GPS yang baru dan cukup akurat."); return; }
            copyCoordinates(fix.getLatitude(), fix.getLongitude());
        });
        Ui.add(location, copyGps, 8);
        Button openGpsMap = Ui.button(this, "Buka Google Maps", Ui.TEAL);
        openGpsMap.setOnClickListener(v -> {
            Location fix = gps.freshFix();
            if (fix == null) { toast("Tunggu koordinat GPS yang baru dan cukup akurat."); return; }
            openInMaps(fix.getLatitude(), fix.getLongitude(), "Lokasi HIFAKI Saat Ini");
        });
        Ui.add(location, openGpsMap, 8);
        Ui.add(location, Ui.text(this, "Koordinat disimpan saat pemeriksaan disimpan, lalu ikut dikirim jika sinkronisasi online diaktifkan. Perlu langit terbuka dan izin Lokasi presisi.", 12, false, Ui.MUTED), 0);
        Ui.add(body, location, 12);

        LinearLayout info = Ui.card(this);
        freshnessText = Ui.text(this, "Menunggu data sensor", 13, false, Ui.MUTED);
        Ui.add(info, freshnessText, 0);
        Ui.add(body, info, 12);

        LinearLayout exam = Ui.card(this);
        Ui.add(exam, Ui.text(this, "Catat pemeriksaan", 19, true, Ui.NAVY), 7);
        assessmentText = Ui.text(this, Assessment.label(verifiedCore, danger), 15, true, Ui.TEAL);
        Ui.add(exam, assessmentText, 12);
        nameInput = Ui.input(this, "Nama / kode pasien");
        Ui.add(exam, nameInput, 10);
        Button core = Ui.button(this, verifiedCore == null ?
                "Isi suhu inti terverifikasi" : String.format(ID, "Suhu inti: %.1f °C • ubah", verifiedCore), Ui.NAVY);
        core.setOnClickListener(v -> coreDialog());
        Ui.add(exam, core, 6);
        Ui.add(exam, Ui.text(this, "Hanya isi dari termometer medis yang mampu membaca suhu rendah. Jangan salin nilai sensor kontak.", 12, false, Ui.MUTED), 10);
        symptomBox = new CheckBox(this);
        symptomBox.setText("Ada tanda bahaya (bingung, pingsan, napas lambat)");
        symptomBox.setTextSize(13);
        symptomBox.setTextColor(Ui.NAVY);
        symptomBox.setChecked(danger);
        symptomBox.setOnCheckedChangeListener((button, checked) -> {
            danger = checked;
            if (assessmentText != null) updateAssessment();
            if (checked) emergency();
        });
        Ui.add(exam, symptomBox, 12);

        Ui.add(exam, Ui.text(this, "Foto orang yang diperiksa (opsional)", 16, true, Ui.NAVY), 8);
        photoPreview = new ImageView(this);
        photoPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photoPreview.setBackground(Ui.bg(this, 0xffe4f4f3, 14));
        exam.addView(photoPreview, new LinearLayout.LayoutParams(-1, Ui.dp(this, 188)));
        photoNote = Ui.text(this, "Belum ada foto.", 12, false, Ui.MUTED);
        Ui.add(exam, photoNote, 8);
        Button camera = Ui.button(this, "Ambil foto kamera", Ui.TEAL);
        camera.setOnClickListener(v -> takePhoto());
        Ui.add(exam, camera, 7);
        Button gallery = Ui.button(this, "Pilih dari galeri", Ui.NAVY);
        gallery.setOnClickListener(v -> choosePhoto());
        Ui.add(exam, gallery, 7);
        Button remove = Ui.button(this, "Hapus foto pilihan", Ui.MUTED);
        remove.setOnClickListener(v -> {
            if (photoProcessing) { toast("Tunggu foto selesai diproses."); return; }
            photos.deleteDraft(draftPhoto);
            draftPhoto = null;
            updatePhotoUi();
        });
        Ui.add(exam, remove, 8);
        Ui.add(exam, Ui.text(this, "Foto disimpan privat di ponsel dan tidak ikut dikirim ke server.", 12, false, Ui.MUTED), 12);
        Button save = Ui.button(this, "Simpan Pemeriksaan", Ui.RED);
        save.setOnClickListener(v -> saveRecord());
        Ui.add(exam, save, 0);
        Ui.add(body, exam, 0);
        updatePhotoUi();
        if (gps.hasPrecisePermission()) gps.start();
        updateMonitor();
    }

    private void takePhoto() {
        if (photoProcessing) { toast("Tunggu foto selesai diproses."); return; }
        try {
            File file = photos.newCaptureFile();
            captureFile = file;
            cameraLauncher.launch(photos.captureUri(file));
        } catch (IOException | RuntimeException e) {
            photos.deleteDraft(captureFile);
            captureFile = null;
            toast("Kamera tidak dapat dibuka di ponsel ini.");
        }
    }

    private void choosePhoto() {
        if (photoProcessing) { toast("Tunggu foto selesai diproses."); return; }
        try { galleryLauncher.launch(new String[]{"image/*"}); }
        catch (RuntimeException e) { toast("Galeri tidak tersedia di ponsel ini."); }
    }

    private void processPhoto(Uri uri, File captured) {
        photoProcessing = true;
        updatePhotoUi();
        new Thread(() -> {
            File imported = null;
            try { imported = photos.importImage(uri); }
            catch (IOException | RuntimeException ignored) { }
            finally { photos.deleteDraft(captured); }
            File result = imported;
            runOnUiThread(() -> {
                photoProcessing = false;
                if (isDestroyed()) { photos.deleteDraft(result); return; }
                if (result == null) toast("Foto gagal dibaca. Pilih JPEG atau PNG lain.");
                else {
                    photos.deleteDraft(draftPhoto);
                    draftPhoto = result;
                }
                updatePhotoUi();
            });
        }, "HifakiPhoto").start();
    }

    private void updatePhotoUi() {
        if (photoPreview == null || photoNote == null || page != 1) return;
        Bitmap bitmap = photos.preview(draftPhoto);
        photoPreview.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
        photoPreview.setImageBitmap(bitmap);
        photoNote.setText(photoProcessing ? "Memproses foto…" : bitmap == null ?
                "Belum ada foto. Pemeriksaan tetap bisa disimpan." : "Foto siap disimpan bersama pemeriksaan.");
    }

    private void updateAssessment() {
        assessmentText.setText(Assessment.label(verifiedCore, danger));
        assessmentText.setTextColor(Assessment.urgent(verifiedCore, danger) ? Ui.RED : Ui.TEAL);
    }

    private void updateMonitor() {
        if (skinText == null || page != 1) return;
        boolean fresh = current != null && System.currentTimeMillis() - current.receivedAt < 10000;
        Reading r = fresh ? current : null;
        skinText.setText(r != null && r.skinC != null ? String.format(ID, "%.2f °C", r.skinC) : "— °C");
        heartText.setText(r != null && r.bpm != null ? r.bpm + " bpm" : "— bpm");
        if (spo2Text != null) spo2Text.setText(r != null && r.spo2 != null ? r.spo2 + " %" : "— %");
        altitudeText.setText(r != null && r.altitudeM != null ? r.altitudeM + " mdpl" : "— mdpl");
        freshnessText.setText(r == null ? "Data tidak tersedia / sudah kedaluwarsa" :
                "Data baru • " + new SimpleDateFormat("HH:mm:ss", ID).format(new Date(r.receivedAt)));
        if (assessmentText != null) updateAssessment();
        updateGpsUi();
    }

    private void updateGpsUi() {
        if (page != 1 || gpsCoordinates == null || gpsStatus == null || gps == null) return;
        Location fix = gps.freshFix();
        gpsCoordinates.setText(fix == null ? "—" :
                String.format(Locale.US, "%.6f, %.6f", fix.getLatitude(), fix.getLongitude()));
        String detail = gps.status();
        if (fix == null && "Sinyal GPS diterima".equals(detail))
            detail = "Menunggu akurasi ≤500 m; koordinat lama (>60 detik) tidak dipakai";
        if (fix != null)
            detail = String.format(ID, "Akurasi ±%.0f m • diperbarui %s", fix.getAccuracy(),
                    new SimpleDateFormat("HH:mm:ss", ID).format(new Date(fix.getTime())));
        gpsStatus.setText(detail);
    }

    private void startGpsFlow() {
        if (!gps.hasPrecisePermission()) {
            if (Build.VERSION.SDK_INT >= 31)
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION}, GPS_REQUEST);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, GPS_REQUEST);
            return;
        }
        if (!gps.isGpsEnabled()) {
            toast("Aktifkan Lokasi/GPS ponsel, lalu kembali ke HIFAKI.");
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        gps.stop();
        gps.start();
        updateGpsUi();
    }

    private void copyCoordinates(double latitude, double longitude) {
        String value = String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Koordinat HIFAKI", value));
        toast("Koordinat disalin.");
    }

    private void coreDialog() {
        LinearLayout form = Ui.column(this);
        form.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), 0);
        EditText input = Ui.input(this, "Contoh: 34,5");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        if (verifiedCore != null) input.setText(String.format(ID, "%.1f", verifiedCore));
        Ui.add(form, input, 8);
        CheckBox confirm = new CheckBox(this);
        confirm.setText("Diukur dengan termometer medis berjangkauan suhu rendah");
        confirm.setTextSize(13);
        Ui.add(form, confirm, 4);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Suhu inti terverifikasi")
                .setView(form)
                .setNeutralButton("Hapus nilai", (d, which) -> { verifiedCore = null; render(); })
                .setNegativeButton("Batal", null)
                .setPositiveButton("Gunakan", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                float value = Float.parseFloat(input.getText().toString().trim().replace(',', '.'));
                if (!Float.isFinite(value) || value < 0 || value > 45 || !confirm.isChecked()) {
                    toast("Masukkan 0–45 °C dan centang asal pengukuran."); return;
                }
                verifiedCore = value;
                dialog.dismiss();
                String name = nameInput == null ? "" : nameInput.getText().toString();
                render();
                if (nameInput != null) nameInput.setText(name);
                if (Assessment.urgent(verifiedCore, danger)) emergency();
            } catch (NumberFormatException e) { toast("Masukkan angka suhu yang valid."); }
        }));
        dialog.show();
    }

    private void emergency() {
        new AlertDialog.Builder(this).setTitle("Perlu pertolongan medis segera")
                .setMessage("Jika suhu inti di bawah 35 °C atau ada gejala berat, cari bantuan medis segera. Pindahkan ke tempat hangat, ganti pakaian basah dan selimuti. Hindari pemanasan langsung yang terlalu panas.")
                .setPositiveButton("Mengerti", null).show();
    }

    private void saveRecord() {
        if (photoProcessing) { toast("Tunggu foto selesai diproses."); return; }
        String name = nameInput.getText().toString().trim();
        if (name.length() < 2 || name.length() > 80) { toast("Isi nama atau kode pasien (2–80 karakter)."); return; }
        Reading valid = current != null && System.currentTimeMillis() - current.receivedAt < 10000 ? current : null;
        if ((valid == null || !valid.hasData()) && verifiedCore == null && !danger) {
            toast("Tunggu data sensor, isi suhu inti, atau catat tanda bahaya."); return;
        }
        Location fix = gps.freshFix();
        String photoFile = null;
        String recordId = null;
        try {
            photoFile = photos.persist(draftPhoto);
            recordId = store.save(name, valid, verifiedCore, danger, fix, photoFile);
            pushRecordToFirebase(recordId, name, valid, verifiedCore, danger, fix, photoFile);
        } catch (Exception e) {
            photos.deleteSaved(photoFile);
            toast("Penyimpanan gagal. Periksa ruang penyimpanan dan coba lagi.");
            return;
        }
        photos.deleteDraft(draftPhoto);
        draftPhoto = null;
        updatePhotoUi();
        SyncJob.schedule(this);
        toast(fix == null ? "Tersimpan tanpa GPS; menunggu lokasi tidak menghalangi pemeriksaan." :
                "Tersimpan dengan koordinat GPS di ponsel.");
        verifiedCore = null;
        danger = false;
        nameInput.setText("");
        symptomBox.setChecked(false);
        updateAssessment();
    }

    private void startBle() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, NEARBY_REQUEST);
                return;
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, NEARBY_REQUEST);
            return;
        }
        if (!ble.available()) {
            new AlertDialog.Builder(this).setMessage("Aktifkan Bluetooth pada ponsel, lalu ketuk Cari lagi.")
                    .setPositiveButton("Mengerti", null).show();
            return;
        }
        ble.scan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == GPS_REQUEST) {
            if (gps.hasPrecisePermission()) gps.start();
            else toast("Untuk GPS satelit, pilih Lokasi presisi pada izin aplikasi.");
            updateGpsUi();
            return;
        }
        if (requestCode != NEARBY_REQUEST) return;
        for (int grant : grants) if (grant != PackageManager.PERMISSION_GRANTED) {
            toast("Izin perangkat terdekat diperlukan untuk BLE."); return;
        }
        if (grants.length > 0) startBle();
    }

    private void history() {
        LinearLayout body = scrollPage();
        section(body, "Riwayat", "Tersimpan di ponsel, termasuk saat tidak ada sinyal.");
        EditText search = Ui.input(this, "Cari nama / kode pasien");
        Ui.add(body, search, 14);
        TextView pending = Ui.text(this, store.pendingCount() + " catatan menunggu sinkron", 13, true, Ui.TEAL);
        Ui.add(body, pending, 12);
        LinearLayout rows = Ui.column(this);
        Ui.add(body, rows, 0);
        List<Store.Record> all = store.recent();
        fillHistory(rows, all, "");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                fillHistory(rows, all, s.toString());
            }
            @Override public void afterTextChanged(Editable e) { }
        });
    }

    private void fillHistory(LinearLayout rows, List<Store.Record> all, String query) {
        rows.removeAllViews();
        int matches = 0;
        for (Store.Record record : all) {
            if (!record.name.toLowerCase(ID).contains(query.toLowerCase(ID))) continue;
            matches++;
            LinearLayout card = Ui.card(this);

            LinearLayout headerRow = new LinearLayout(this);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout textCol = Ui.column(this);
            Ui.add(textCol, Ui.text(this, record.name, 18, true, Ui.NAVY), 3);
            Ui.add(textCol, Ui.text(this, record.status, 14, true,
                    Assessment.urgent(record.coreC, record.danger) ? Ui.RED : Ui.TEAL), 0);

            headerRow.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));

            Bitmap photo = photos.previewSaved(record.photoFile);
            ImageView thumb = new ImageView(this);
            if (photo != null) {
                thumb.setImageBitmap(photo);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(Ui.bg(this, 0xffe4f4f3, 10));
                thumb.setOnClickListener(v -> showFullPhoto(record.photoFile, record.name));
            } else {
                thumb.setImageResource(R.drawable.ic_hifaki);
                thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
                thumb.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
                thumb.setBackground(Ui.bg(this, 0xfff0f4f8, 10));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60));
            lp.setMargins(Ui.dp(this, 8), 0, 0, 0);
            headerRow.addView(thumb, lp);

            Ui.add(card, headerRow, 8);
            Ui.add(card, Ui.text(this, "Suhu kontak " + f(record.skinC, "°C") + "   •   Nadi " + f(record.bpm, "bpm") + "   •   SpO2 " + f(record.spo2, "%"), 13, false, Ui.NAVY), 5);
            Ui.add(card, Ui.text(this, "Suhu inti " + f(record.coreC, "°C") + "   •   " +
                    (record.synced ? "Tersinkron" : "Lokal / menunggu kirim"), 12, false, Ui.MUTED), 5);
            if (record.latitude != null && record.longitude != null) {
                TextView gpsText = Ui.text(this, String.format(Locale.US, "🗺️ GPS %.6f, %.6f (Buka Peta)", record.latitude, record.longitude), 12, false, Ui.TEAL);
                gpsText.setOnClickListener(v -> openInMaps(record.latitude, record.longitude, record.name));
                Ui.add(card, gpsText, 5);
            }
            Ui.add(card, Ui.text(this, new SimpleDateFormat("dd MMM yyyy • HH:mm", ID).format(new Date(record.time)), 12, false, Ui.MUTED), 0);

            card.setOnClickListener(v -> detail(record));
            card.setOnLongClickListener(v -> { deleteDialog(record); return true; });
            Ui.add(rows, card, 12);
        }
        if (matches == 0) Ui.add(rows, Ui.text(this, "Belum ada catatan yang cocok.", 15, false, Ui.MUTED), 0);
    }

    private void detail(Store.Record r) {
        String location = r.latitude == null || r.longitude == null ? "— (tidak direkam)" :
                String.format(Locale.US, "%.6f, %.6f", r.latitude, r.longitude) +
                (r.gpsAccuracyM == null ? "" : String.format(ID, " (±%.0f m)", r.gpsAccuracyM)) +
                (r.gpsAt == null ? "" : " • " + new SimpleDateFormat("HH:mm:ss", ID).format(new Date(r.gpsAt)));
        String body = "Waktu: " + new SimpleDateFormat("dd MMMM yyyy HH:mm:ss", ID).format(new Date(r.time)) +
                "\nSuhu kontak: " + f(r.skinC, "°C") + "\nNadi: " + f(r.bpm, "bpm") + "\nSpO2: " + f(r.spo2, "%") +
                "\nKetinggian estimasi: " + f(r.altitudeM, "m dpl") +
                "\nGPS: " + location +
                "\nSuhu inti terverifikasi: " + f(r.coreC, "°C") +
                "\nGejala bahaya: " + (r.danger ? "Ya" : "Tidak dicatat") +
                "\nStatus: " + r.status + "\nSinkron: " + (r.synced ? "Tersinkron" : "Lokal / menunggu kirim");

        Bitmap savedPhoto = photos.previewSaved(r.photoFile);

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = Ui.column(this);
        layout.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 16));

        if (savedPhoto != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(savedPhoto);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setOnClickListener(v -> showFullPhoto(r.photoFile, r.name));
            layout.addView(image, new LinearLayout.LayoutParams(-1, Ui.dp(this, 220)));

            Button viewPhotoBtn = Ui.button(this, "🔍 Buka Foto Ukuran Penuh", Ui.TEAL);
            viewPhotoBtn.setOnClickListener(v -> showFullPhoto(r.photoFile, r.name));
            Ui.add(layout, viewPhotoBtn, 12);
        } else {
            ImageView placeholder = new ImageView(this);
            placeholder.setImageResource(R.drawable.ic_hifaki);
            placeholder.setScaleType(ImageView.ScaleType.FIT_CENTER);
            placeholder.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
            placeholder.setBackground(Ui.bg(this, 0xfff0f4f8, 12));
            layout.addView(placeholder, new LinearLayout.LayoutParams(-1, Ui.dp(this, 100)));
            Ui.add(layout, Ui.text(this, "Foto: Tidak ada foto (tidak diambil saat menyimpan)", 12, false, Ui.MUTED), 12);
        }

        double mapLat = 0, mapLng = 0;
        boolean hasGps = false;
        if (r.latitude != null && r.longitude != null) {
            mapLat = r.latitude;
            mapLng = r.longitude;
            hasGps = true;
        } else if (gps != null) {
            Location fresh = gps.freshFix();
            if (fresh != null) {
                mapLat = fresh.getLatitude();
                mapLng = fresh.getLongitude();
                hasGps = true;
            }
        }

        if (hasGps) {
            final double targetLat = mapLat;
            final double targetLng = mapLng;
            Button openMapBtn = Ui.button(this, "🗺️ Buka Peta Google Maps", Ui.NAVY);
            openMapBtn.setOnClickListener(v -> openInMaps(targetLat, targetLng, r.name));
            Ui.add(layout, openMapBtn, 12);
        }

        Ui.add(layout, Ui.text(this, body + (r.photoFile != null ? "\nFoto: tersimpan privat di ponsel" : ""), 14, false, Ui.NAVY), 0);
        scroll.addView(layout);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(r.name)
                .setView(scroll)
                .setNegativeButton("Tutup", null)
                .setNeutralButton("Hapus Catatan", (d, which) -> deleteDialog(r));

        if (hasGps) {
            final double targetLat = mapLat;
            final double targetLng = mapLng;
            builder.setPositiveButton("Buka Peta", (d, which) -> openInMaps(targetLat, targetLng, r.name));
        }

        builder.show();
    }

    private void showFullPhoto(String photoFile, String title) {
        Bitmap full = photos.fullSaved(photoFile);
        if (full == null) {
            toast("Foto tidak tersedia.");
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView view = new ImageView(this);
        view.setImageBitmap(full);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setBackgroundColor(Color.BLACK);

        AlertDialog dialog = builder.setView(view)
                .setPositiveButton("Tutup", null)
                .create();
        view.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void openInMaps(double latitude, double longitude, String label) {
        try {
            String uriStr = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
                    latitude, longitude, latitude, longitude, Uri.encode(label));
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriStr));
            startActivity(intent);
        } catch (Exception e) {
            try {
                String webUrl = String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", latitude, longitude);
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)));
            } catch (Exception ignored) {
                toast("Aplikasi peta tidak dapat dibuka di ponsel ini.");
            }
        }
    }

    private void deleteDialog(Store.Record r) {
        new AlertDialog.Builder(this).setTitle("Hapus catatan?")
                .setMessage("Catatan " + r.name + " akan dihapus dari ponsel. Jika sudah tersinkron, salinan di server tetap ada.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (d, which) -> {
                    store.delete(r.id);
                    photos.deleteSaved(r.photoFile);
                    render();
                }).show();
    }

    private void settings() {
        LinearLayout body = scrollPage();
        section(body, "Pengaturan", "Online opsional; BLE dan riwayat tetap berjalan tanpa server.");

        LinearLayout about = Ui.card(this);
        Ui.add(about, Ui.text(this, "TENTANG APLIKASI", 12, true, Ui.TEAL), 8);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_hifaki);
        titleRow.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        LinearLayout textCol = Ui.column(this);
        textCol.setPadding(Ui.dp(this, 10), 0, 0, 0);
        Ui.add(textCol, Ui.text(this, "HIFAKI", 20, true, 0xffaa0000), 2);
        Ui.add(textCol, Ui.text(this, "Hypothermia First Aid Kit", 13, false, Ui.NAVY), 0);
        titleRow.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));

        Ui.add(about, titleRow, 12);

        String verName = "1.2.0";
        try {
            verName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }

        Ui.add(about, Ui.text(this, "Versi Aplikasi: v" + verName + " (Build 4)", 14, true, Ui.NAVY), 8);
        Ui.add(about, Ui.text(this, "Sistem Pertolongan Pertama & Skrining Hipotermia Lapangan Terintegrasi IoT & Cloud.", 14, false, Ui.MUTED), 12);

        Ui.add(about, Ui.text(this, "SPESIFIKASI SISTEM & HARDWARE", 12, true, Ui.TEAL), 6);
        Ui.add(about, Ui.text(this, "• Mikrokontroler: ESP32-C3 SuperMini\n" +
                "• Sensor Suhu Kontak: DS18B20\n" +
                "• Sensor Nadi / Pulse: MAX30102\n" +
                "• Sensor Tekanan & Altitude: BMP280\n" +
                "• Pemanas: Warmer Jacket Relay Module\n" +
                "• Koneksi: Dual Mode (BLE Offline & Firebase RTDB)", 13, false, Ui.NAVY), 12);

        Ui.add(about, Ui.text(this, "© 2026 HIFAKI Team • Hak Cipta Dilindungi", 12, false, Ui.MUTED), 0);
        Ui.add(body, about, 14);

        LinearLayout mode = Ui.card(this);
        Ui.add(mode, Ui.text(this, "MODE OFFLINE", 13, true, Ui.TEAL), 8);
        Ui.add(mode, Ui.text(this, "Alat ↔ Bluetooth ↔ aplikasi. Semua catatan pertama kali disimpan di ponsel.", 15, false, Ui.NAVY), 10);
        Ui.add(mode, Ui.text(this, "MODE ONLINE", 13, true, Ui.TEAL), 8);
        Ui.add(mode, Ui.text(this, "Catatan yang tertunda dikirim lewat HTTPS saat internet tersedia. Server bersifat opsional.", 15, false, Ui.NAVY), 0);
        Ui.add(body, mode, 14);

        SecurePrefs prefs = new SecurePrefs(this);
        LinearLayout cloud = Ui.card(this);
        Ui.add(cloud, Ui.text(this, "Server sinkronisasi", 19, true, Ui.NAVY), 8);
        EditText endpoint = Ui.input(this, "https://domain-anda/hifaki/api.php");
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setText(prefs.url());
        Ui.add(cloud, endpoint, 9);
        EditText token = Ui.input(this, "Kunci API");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        try { token.setText(prefs.token()); }
        catch (Exception e) { toast("Kunci tersimpan tidak dapat dibuka; isi ulang kunci API."); }
        Ui.add(cloud, token, 9);
        Button save = Ui.button(this, "Simpan server", Ui.NAVY);
        save.setOnClickListener(v -> {
            String url = endpoint.getText().toString().trim();
            String key = token.getText().toString().trim();
            try {
                if (!url.isEmpty()) {
                    URL parsed = new URL(url);
                    if (!"https".equalsIgnoreCase(parsed.getProtocol()) || parsed.getHost().isEmpty() ||
                            parsed.getUserInfo() != null || key.isEmpty()) {
                        toast("Gunakan HTTPS dan isi kunci API."); return;
                    }
                }
                prefs.save(url, url.isEmpty() ? "" : key);
                SyncJob.schedule(this);
                toast(url.isEmpty() ? "Mode online dimatikan." : "Server tersimpan; antrean siap dikirim.");
                render();
            } catch (Exception e) { toast("Alamat atau kunci tidak dapat disimpan."); }
        });
        Ui.add(cloud, save, 10);
        Button sync = Ui.button(this, "Sinkron sekarang", Ui.TEAL);
        sync.setOnClickListener(v -> {
            sync.setEnabled(false);
            syncPendingRecordsToFirebase();
            new Thread(() -> {
                SyncEngine.Result result = SyncEngine.upload(getApplicationContext(), new AtomicBoolean(false));
                runOnUiThread(() -> {
                    toast(result.error == null ? result.uploaded + " catatan tersinkron." : result.error);
                    render();
                });
            }, "ManualSync").start();
        });
        Ui.add(cloud, sync, 0);
        Ui.add(body, cloud, 14);

        LinearLayout note = Ui.card(this);
        Ui.add(note, Ui.text(this, "Persiapan alat", 18, true, Ui.NAVY), 8);
        Ui.add(note, Ui.text(this, "ESP32-C3 SuperMini • DS18B20 • MAX30102 • BMP280. Saat pertama kali, pairing memakai kode unik yang tampil pada Serial Monitor ketika alat disiapkan.", 14, false, Ui.MUTED), 8);
        Ui.add(note, Ui.text(this, "Sebelum masuk lapangan, pasangkan alat dan tempelkan kode pada kemasan yang hanya dapat diakses petugas.", 13, false, Ui.TEAL), 0);
        Ui.add(body, note, 0);
    }

    private void toggleWarmerState() {
        warmerOn = !warmerOn;
        int pwm = warmerOn ? 100 : 0;
        updateWarmerUi();
        
        // Kirim perintah langsung ke ESP32 melalui BLE (Offline)
        if (ble != null) {
            String command = warmerOn ? "RELAY_ON" : "RELAY_OFF";
            ble.sendCommand(command);
        }
        
        if (mDatabase != null) {
            try {
                Map<String, Object> update = new HashMap<>();
                update.put("heater_on", warmerOn);
                update.put("heater_pwm", pwm);
                update.put("relay", warmerOn ? 1 : 0);
                update.put("warmer", warmerOn);
                update.put("updatedAt", System.currentTimeMillis());
                mDatabase.child("live_sensor").updateChildren(update);
            } catch (Exception ignored) { }
        }
        toast(warmerOn ? "Jaket Penghangat dinyalakan (ON)" : "Jaket Penghangat dimatikan (OFF)");
    }

    private void updateWarmerUi() {
        if (warmerStatusText != null && page == 1) {
            warmerStatusText.setText(warmerOn ? "Pemanas Jaket: AKTIF (ON)" : "Pemanas Jaket: MATI (OFF)");
            warmerStatusText.setTextColor(warmerOn ? Ui.RED : Ui.NAVY);
        }
        if (warmerButton != null && page == 1) {
            warmerButton.setText(warmerOn ? "Matikan Jaket Penghangat (OFF)" : "Nyalakan Jaket Penghangat (ON)");
            warmerButton.setBackground(Ui.bg(this, warmerOn ? Ui.RED : Ui.TEAL, 12));
        }
    }

    private void pushLiveSensorToFirebase(Reading reading) {
        if (mDatabase == null) return;
        try {
            Map<String, Object> liveMap = new HashMap<>();
            if (reading != null) {
                liveMap.put("skinC", reading.skinC);
                liveMap.put("skin_c", reading.skinC);
                liveMap.put("core_c", reading.skinC);
                liveMap.put("bpm", reading.bpm);
                liveMap.put("spo2", reading.spo2);
                liveMap.put("altitudeM", reading.altitudeM);
                liveMap.put("altitude_mdpl", reading.altitudeM);
                liveMap.put("sequence", reading.sequence);
                liveMap.put("receivedAt", reading.receivedAt);
            }
            liveMap.put("heater_on", warmerOn);
            liveMap.put("heater_pwm", warmerOn ? 100 : 0);
            liveMap.put("relay", warmerOn ? 1 : 0);
            liveMap.put("warmer", warmerOn);
            liveMap.put("bleStatus", bleStatus);
            if (gps != null) {
                Location fix = gps.freshFix();
                if (fix != null) {
                    liveMap.put("latitude", fix.getLatitude());
                    liveMap.put("longitude", fix.getLongitude());
                    liveMap.put("gpsAccuracyM", fix.hasAccuracy() ? fix.getAccuracy() : null);
                    liveMap.put("gpsAt", fix.getTime());
                }
            }
            liveMap.put("updatedAt", System.currentTimeMillis());
            mDatabase.child("live_sensor").setValue(liveMap);
        } catch (Exception ignored) { }
    }

    private void pushRecordToFirebase(String id, String name, Reading reading, Float coreC, boolean danger, Location gpsFix, String photoFile) {
        if (mDatabase == null || id == null) return;
        try {
            long now = System.currentTimeMillis();
            Map<String, Object> map = new HashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("measured_at", now);
            map.put("status", Assessment.label(coreC, danger));
            map.put("danger", danger);
            map.put("skin_c", reading == null ? null : reading.skinC);
            map.put("bpm", reading == null ? null : reading.bpm);
            map.put("spo2", reading == null ? null : reading.spo2);
            map.put("altitude_m", reading == null ? null : reading.altitudeM);
            map.put("core_c", coreC);
            map.put("latitude", gpsFix == null ? null : gpsFix.getLatitude());
            map.put("longitude", gpsFix == null ? null : gpsFix.getLongitude());
            map.put("gps_accuracy_m", gpsFix == null || !gpsFix.hasAccuracy() ? null : gpsFix.getAccuracy());
            map.put("gps_at", gpsFix == null ? null : gpsFix.getTime());
            map.put("source", "HIFAKI-C3");

            if (photoFile != null) {
                map.put("photo_file", photoFile);
                String base64 = photos.getBase64(photoFile);
                if (base64 != null) map.put("photo_base64", base64);
            }

            mDatabase.child("records").child(id).setValue(map)
                    .addOnSuccessListener(aVoid -> {
                        try { store.markSynced(id); } catch (Exception ignored) { }
                    });
        } catch (Exception ignored) { }
    }

    private void syncPendingRecordsToFirebase() {
        if (mDatabase == null || store == null) return;
        try {
            List<Store.Record> pending = store.pending();
            for (Store.Record r : pending) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", r.id);
                map.put("name", r.name);
                map.put("measured_at", r.time);
                map.put("status", r.status);
                map.put("danger", r.danger);
                map.put("skin_c", r.skinC);
                map.put("bpm", r.bpm);
                map.put("spo2", r.spo2);
                map.put("altitude_m", r.altitudeM);
                map.put("core_c", r.coreC);
                map.put("latitude", r.latitude);
                map.put("longitude", r.longitude);
                map.put("gps_accuracy_m", r.gpsAccuracyM);
                map.put("gps_at", r.gpsAt);
                map.put("source", "HIFAKI-C3");

                if (r.photoFile != null) {
                    map.put("photo_file", r.photoFile);
                    String base64 = photos.getBase64(r.photoFile);
                    if (base64 != null) map.put("photo_base64", base64);
                }

                final String recordId = r.id;
                mDatabase.child("records").child(recordId).setValue(map)
                        .addOnSuccessListener(aVoid -> {
                            try { store.markSynced(recordId); } catch (Exception ignored) { }
                        });
            }
        } catch (Exception ignored) { }
    }

    private void listenForFirebaseRecords() {
        if (mDatabase == null || recordsListener != null) return;
        recordsListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                new Thread(() -> {
                    try {
                        for (DataSnapshot child : snapshot.getChildren()) {
                            String id = child.child("id").getValue(String.class);
                            if (id == null || id.isEmpty()) id = child.getKey();
                            String name = child.child("name").getValue(String.class);
                            Long measuredAt = getLongTimestamp(child, "measured_at", "measuredAt", "time", "timestamp", "created_at", "createdAt", "date", "datetime");
                            if (measuredAt == null) measuredAt = System.currentTimeMillis();
                            String status = child.child("status").getValue(String.class);
                            Boolean danger = child.child("danger").getValue(Boolean.class);

                            Float skinC = getFloat(child, "skin_c", "skinC");
                            Integer bpm = getInt(child, "bpm");
                            Integer spo2 = getInt(child, "spo2", "saturasi");
                            Integer altitudeM = getInt(child, "altitude_m", "altitudeM");
                            Float coreC = getFloat(child, "core_c", "coreC");
                            Double latitude = getDouble(child, "latitude");
                            Double longitude = getDouble(child, "longitude");
                            Float gpsAccuracyM = getFloat(child, "gps_accuracy_m", "gpsAccuracyM");
                            Long gpsAt = getLongTimestamp(child, "gps_at", "gpsAt");

                            String photoFile = child.child("photo_file").getValue(String.class);
                            String photoBase64 = child.child("photo_base64").getValue(String.class);
                            if (photoBase64 == null) photoBase64 = child.child("photo").getValue(String.class);
                            if (photoBase64 == null) photoBase64 = child.child("photo_data").getValue(String.class);

                            if (photoBase64 != null && !photoBase64.trim().isEmpty()) {
                                String savedName = photos.saveFromBase64(photoBase64);
                                if (savedName != null) photoFile = savedName;
                            }

                            store.upsertRemoteRecord(id, name, measuredAt, status, danger != null && danger,
                                    skinC, bpm, spo2, altitudeM, coreC, latitude, longitude, gpsAccuracyM, gpsAt, photoFile);
                        }
                        runOnUiThread(() -> {
                            if (page == 2) render();
                        });
                    } catch (Exception ignored) { }
                }, "FirebaseSyncThread").start();
            }

            @Override
            public void onCancelled(DatabaseError error) { }
        };
        mDatabase.child("records").addValueEventListener(recordsListener);
    }

    private void listenForFirebaseLiveSensor() {
        if (mDatabase == null || liveSensorListener != null) return;
        liveSensorListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                try {
                    Float coreC = getFloat(snapshot, "core_c", "coreC");
                    Float skinC = getFloat(snapshot, "skinC", "skin_c", "temp_c", "tempC", "temperature", "temp", "suhu", "suhu_c", "suhu_kontak");
                    Float temp = (coreC != null && coreC > 0) ? coreC : (skinC != null && skinC > 0 ? skinC : (coreC != null ? coreC : skinC));

                    Integer bpm = getInt(snapshot, "bpm", "heart_rate", "pulse", "nadi", "hr");
                    Integer spo2 = getInt(snapshot, "spo2", "saturasi");
                    Integer altitudeM = getInt(snapshot, "altitude_mdpl", "altitudeM", "altitude_m", "altitude", "alt", "ketinggian", "height");
                    Integer sequence = getInt(snapshot, "sequence");

                    Boolean heaterOn = snapshot.child("heater_on").getValue(Boolean.class);
                    Integer pwm = getInt(snapshot, "heater_pwm", "heaterPwm");
                    Integer relay = getInt(snapshot, "relay");
                    Boolean warmer = snapshot.child("warmer").getValue(Boolean.class);

                    if (heaterOn != null || pwm != null || relay != null || warmer != null) {
                        warmerOn = (heaterOn != null && heaterOn) ||
                                   (pwm != null && pwm > 0) ||
                                   (relay != null && relay > 0) ||
                                   (warmer != null && warmer);
                        runOnUiThread(() -> updateWarmerUi());
                    }

                    // ABAIKAN data dari Firebase jika sedang terkoneksi via BLE (Offline)
                    if (ble != null && ble.isConnected()) {
                        return;
                    }

                    long now = System.currentTimeMillis();

                    if (temp != null || bpm != null || spo2 != null || altitudeM != null) {
                        current = new Reading(temp, bpm, spo2, altitudeM, null,
                                sequence != null ? sequence : 0, now);
                        runOnUiThread(() -> {
                            if (page == 1) updateMonitor();
                        });
                    }
                } catch (Exception ignored) { }
            }

            @Override
            public void onCancelled(DatabaseError error) { }
        };
        mDatabase.child("live_sensor").addValueEventListener(liveSensorListener);
    }

    private Long getLongTimestamp(DataSnapshot snapshot, String... keys) {
        for (String key : keys) {
            DataSnapshot child = snapshot.child(key);
            if (child.exists() && child.getValue() != null) {
                Object val = child.getValue();
                if (val instanceof Number) {
                    long l = ((Number) val).longValue();
                    if (l > 0 && l < 10000000000L) l *= 1000L;
                    return l;
                }
                if (val instanceof String) {
                    String str = ((String) val).trim();
                    try {
                        long l = Long.parseLong(str);
                        if (l > 0 && l < 10000000000L) l *= 1000L;
                        return l;
                    } catch (Exception ignored) { }
                    String[] formats = {
                        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                        "yyyy-MM-dd'T'HH:mm:ss",
                        "yyyy-MM-dd HH:mm:ss",
                        "yyyy-MM-dd",
                        "dd MMM yyyy HH:mm:ss",
                        "dd MMMM yyyy HH:mm:ss"
                    };
                    for (String fmt : formats) {
                        try {
                            SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.ROOT);
                            Date parsed = sdf.parse(str);
                            if (parsed != null) return parsed.getTime();
                        } catch (Exception ignored) { }
                    }
                }
            }
        }
        return null;
    }

    private Float getFloat(DataSnapshot snapshot, String... keys) {
        for (String key : keys) {
            DataSnapshot child = snapshot.child(key);
            if (child.exists() && child.getValue() != null) {
                Object val = child.getValue();
                if (val instanceof Number) return ((Number) val).floatValue();
                if (val instanceof String) {
                    try { return Float.parseFloat((String) val); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private Integer getInt(DataSnapshot snapshot, String... keys) {
        for (String key : keys) {
            DataSnapshot child = snapshot.child(key);
            if (child.exists() && child.getValue() != null) {
                Object val = child.getValue();
                if (val instanceof Number) return ((Number) val).intValue();
                if (val instanceof String) {
                    try { return Integer.parseInt((String) val); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private Double getDouble(DataSnapshot snapshot, String... keys) {
        for (String key : keys) {
            DataSnapshot child = snapshot.child(key);
            if (child.exists() && child.getValue() != null) {
                Object val = child.getValue();
                if (val instanceof Number) return ((Number) val).doubleValue();
                if (val instanceof String) {
                    try { return Double.parseDouble((String) val); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private static String f(Number value, String unit) {
        if (value == null) return "—";
        return value instanceof Float ? String.format(ID, "%.2f %s", value.floatValue(), unit) : value + " " + unit;
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

}
