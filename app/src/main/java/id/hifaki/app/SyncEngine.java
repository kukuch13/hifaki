package id.hifaki.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

public final class SyncEngine {
    public static final class Result {
        public int uploaded;
        public String error;
        public boolean retry;
    }

    private SyncEngine() { }

    public static Result upload(Context context, AtomicBoolean cancelled) {
        Result result = new Result();
        Store store = new Store(context);
        try {
            SecurePrefs prefs = new SecurePrefs(context);
            String endpoint = prefs.url();
            String token = prefs.token();
            if (endpoint.isEmpty() || token.isEmpty()) {
                result.error = "Isi alamat HTTPS dan kunci API di Pengaturan.";
                return result;
            }
            URL url = new URL(endpoint);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) {
                result.error = "Alamat server wajib HTTPS.";
                return result;
            }
            for (Store.Record r : store.pending()) {
                if (cancelled.get()) { result.retry = true; break; }
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                try {
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("X-Hifaki-Key", token);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setDoOutput(true);
                    JSONObject body = new JSONObject()
                            .put("id", r.id).put("measured_at", r.time)
                            .put("name", r.name).put("status", r.status)
                            .put("danger", r.danger).put("source", "HIFAKI-C3");
                    body.put("skin_c", r.skinC == null ? JSONObject.NULL : r.skinC);
                    body.put("bpm", r.bpm == null ? JSONObject.NULL : r.bpm);
                    body.put("altitude_m", r.altitudeM == null ? JSONObject.NULL : r.altitudeM);
                    body.put("core_c", r.coreC == null ? JSONObject.NULL : r.coreC);
                    body.put("latitude", r.latitude == null ? JSONObject.NULL : r.latitude);
                    body.put("longitude", r.longitude == null ? JSONObject.NULL : r.longitude);
                    body.put("gps_accuracy_m", r.gpsAccuracyM == null ? JSONObject.NULL : r.gpsAccuracyM);
                    body.put("gps_at", r.gpsAt == null ? JSONObject.NULL : r.gpsAt);
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
                    int status = conn.getResponseCode();
                    if (status == 401 || status == 403) {
                        result.error = "Kunci API ditolak server (HTTP " + status + ").";
                        break;
                    }
                    if (status < 200 || status >= 300) {
                        result.error = "Server merespons HTTP " + status + ".";
                        result.retry = status >= 500 || status == 429;
                        break;
                    }
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    try (InputStream in = conn.getInputStream()) {
                        byte[] chunk = new byte[512];
                        int count;
                        while ((count = in.read(chunk)) != -1 && buffer.size() <= 4096) {
                            buffer.write(chunk, 0, count);
                        }
                    }
                    if (buffer.size() > 4096) throw new IllegalStateException("Response too large");
                    String ack = buffer.toString(StandardCharsets.UTF_8.name());
                    JSONObject reply = new JSONObject(ack);
                    if (!reply.optBoolean("saved") || !r.id.equals(reply.optString("id"))) {
                        result.error = "Konfirmasi server tidak cocok.";
                        result.retry = true;
                        break;
                    }
                    store.markSynced(r.id);
                    result.uploaded++;
                } finally { conn.disconnect(); }
            }
            if (store.pendingCount() > 0 && result.error == null) result.retry = true;
        } catch (Exception e) {
            result.error = "Koneksi belum tersedia atau pengaturan tidak valid.";
            result.retry = true;
        } finally { store.close(); }
        return result;
    }
}
