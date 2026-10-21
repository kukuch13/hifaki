package id.hifaki.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;

/** GNSS ponsel saat halaman Monitor aktif; tidak membutuhkan internet atau layanan peta. */
public final class GpsTracker {
    private static final long MAX_AGE_NANOS = 60_000_000_000L;
    private static final float MAX_ACCURACY_M = 500f;
    private final Context context;
    private final LocationManager manager;
    private final Runnable onChange;
    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            latest = new Location(location);
            status = "Sinyal GPS diterima";
            onChange.run();
        }

        @Override public void onProviderDisabled(String provider) {
            latest = null;
            status = "GPS ponsel dimatikan";
            onChange.run();
        }

        @Override public void onProviderEnabled(String provider) {
            status = "Mencari satelit GPS…";
            onChange.run();
        }
    };
    private Location latest;
    private boolean listening;
    private String status = "Ketuk Mulai GPS untuk mencari lokasi";

    public GpsTracker(Context context, Runnable onChange) {
        this.context = context;
        this.onChange = onChange;
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public boolean hasPrecisePermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isGpsEnabled() {
        try { return manager != null && manager.isProviderEnabled(LocationManager.GPS_PROVIDER); }
        catch (IllegalArgumentException e) { return false; }
    }

    public void start() {
        if (listening) return;
        latest = null; // Jangan gunakan koordinat pasien/posisi sebelumnya.
        if (!hasPrecisePermission()) {
            status = "Izin lokasi presisi belum diberikan";
        } else if (!isGpsEnabled()) {
            status = "Aktifkan Lokasi/GPS di pengaturan ponsel";
        } else {
            try {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f,
                        listener, Looper.getMainLooper());
                listening = true;
                status = "Mencari satelit GPS… coba di tempat terbuka";
            } catch (SecurityException e) {
                status = "Izin lokasi presisi belum diberikan";
            } catch (IllegalArgumentException e) {
                status = "Ponsel tidak menyediakan GPS";
            }
        }
        onChange.run();
    }

    public void stop() {
        if (listening) {
            try { manager.removeUpdates(listener); }
            catch (SecurityException ignored) { /* Izin dapat dicabut saat aplikasi aktif. */ }
        }
        listening = false;
        latest = null;
        status = "GPS dijeda";
        onChange.run();
    }

    public String status() { return status; }

    /** Hanya koordinat GPS presisi dan cukup baru yang boleh dicantumkan pada catatan. */
    public Location freshFix() {
        if (!listening || latest == null || !isGpsEnabled() || !hasPrecisePermission() ||
                !latest.hasAccuracy() || latest.getAccuracy() > MAX_ACCURACY_M) return null;
        long age = SystemClock.elapsedRealtimeNanos() - latest.getElapsedRealtimeNanos();
        return age >= 0 && age <= MAX_AGE_NANOS ? new Location(latest) : null;
    }
}
