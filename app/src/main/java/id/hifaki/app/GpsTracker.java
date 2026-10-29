package id.hifaki.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
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
            if (latest != null) {
                // Gunakan lokasi baru jika lebih akurat ATAU lebih baru
                boolean isMoreAccurate = location.hasAccuracy() && latest.hasAccuracy() &&
                        location.getAccuracy() < latest.getAccuracy();
                boolean isNewer = location.getElapsedRealtimeNanos() > latest.getElapsedRealtimeNanos();
                
                if (isMoreAccurate || isNewer) {
                    latest = new Location(location);
                }
            } else {
                latest = new Location(location);
            }
            status = "Sinyal GPS diterima";
            onChange.run();
        }

        @Override public void onProviderDisabled(String provider) {
            if (!isGpsEnabled()) {
                latest = null;
                status = "GPS ponsel dimatikan";
                onChange.run();
            }
        }

        @Override public void onProviderEnabled(String provider) {
            status = "Mencari lokasi…";
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
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 28) {
            return manager.isLocationEnabled();
        } else {
            try {
                return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
                       manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            } catch (IllegalArgumentException e) { return false; }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    public void start() {
        if (listening) return;
        latest = null; // Jangan gunakan koordinat pasien/posisi sebelumnya.
        if (!hasPrecisePermission()) {
            status = "Izin lokasi presisi belum diberikan";
        } else if (!isGpsEnabled()) {
            status = "Aktifkan Lokasi/GPS di pengaturan ponsel";
        } else {
            boolean requested = false;
            try {
                // Provider Jaringan: Mengunci lokasi kasar lebih cepat via BTS/Wi-Fi
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f,
                            listener, Looper.getMainLooper());
                    requested = true;
                }
            } catch (Exception ignored) { }
            
            try {
                // Provider Satelit GPS: Mencari presisi tinggi
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f,
                            listener, Looper.getMainLooper());
                    requested = true;
                }
            } catch (Exception ignored) { }
            
            if (requested) {
                listening = true;
                status = "Mencari lokasi (GPS + Jaringan)…";
            } else {
                status = "Ponsel tidak mendukung pelacakan lokasi";
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
