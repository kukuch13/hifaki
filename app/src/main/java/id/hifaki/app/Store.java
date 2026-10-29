package id.hifaki.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Store extends SQLiteOpenHelper {
    public static final class Record {
        public String id, name, status, photoFile;
        public long time;
        public Float skinC, coreC;
        public Integer bpm, spo2, altitudeM;
        public Double latitude, longitude;
        public Float gpsAccuracyM;
        public Long gpsAt;
        public boolean danger, synced;
    }

    public Store(Context context) { super(context, "hifaki.db", null, 4); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, measured_at INTEGER NOT NULL, " +
                "name TEXT NOT NULL, skin_c REAL, bpm INTEGER, spo2 INTEGER, altitude_m INTEGER, " +
                "core_c REAL, danger INTEGER NOT NULL, status TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0, " +
                "latitude REAL, longitude REAL, gps_accuracy_m REAL, gps_at INTEGER, photo_file TEXT)");
        db.execSQL("CREATE INDEX ix_records_sync ON records(synced, measured_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE records ADD COLUMN latitude REAL");
            db.execSQL("ALTER TABLE records ADD COLUMN longitude REAL");
            db.execSQL("ALTER TABLE records ADD COLUMN gps_accuracy_m REAL");
            db.execSQL("ALTER TABLE records ADD COLUMN gps_at INTEGER");
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE records ADD COLUMN photo_file TEXT");
        if (oldVersion < 4) db.execSQL("ALTER TABLE records ADD COLUMN spo2 INTEGER");
    }

    public String save(String name, Reading reading, Float coreC, boolean danger, Location gps, String photoFile) {
        String id = UUID.randomUUID().toString();
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("measured_at", System.currentTimeMillis());
        v.put("name", name);
        putNullable(v, "skin_c", reading == null ? null : reading.skinC);
        putNullable(v, "bpm", reading == null ? null : reading.bpm);
        putNullable(v, "spo2", reading == null ? null : reading.spo2);
        putNullable(v, "altitude_m", reading == null ? null : reading.altitudeM);
        putNullable(v, "core_c", coreC);
        putNullable(v, "latitude", gps == null ? null : gps.getLatitude());
        putNullable(v, "longitude", gps == null ? null : gps.getLongitude());
        putNullable(v, "gps_accuracy_m", gps == null || !gps.hasAccuracy() ? null : gps.getAccuracy());
        if (gps == null) v.putNull("gps_at"); else v.put("gps_at", gps.getTime());
        if (photoFile == null) v.putNull("photo_file"); else v.put("photo_file", photoFile);
        v.put("danger", danger ? 1 : 0);
        v.put("status", Assessment.label(coreC, danger));
        getWritableDatabase().insertOrThrow("records", null, v);
        return id;
    }

    public void upsertRemoteRecord(String id, String name, long time, String status, boolean danger,
                                   Float skinC, Integer bpm, Integer spo2, Integer altitudeM, Float coreC,
                                   Double latitude, Double longitude, Float gpsAccuracyM, Long gpsAt,
                                   String photoFile) {
        if (id == null || id.isEmpty()) return;

        String resolvedPhoto = photoFile;
        if (resolvedPhoto == null) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT photo_file FROM records WHERE id=?", new String[]{id})) {
                if (c.moveToFirst() && !c.isNull(0)) {
                    resolvedPhoto = c.getString(0);
                }
            } catch (Exception ignored) { }
        }

        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("measured_at", time);
        v.put("name", name == null ? "Tanpa nama" : name);
        v.put("status", status == null ? Assessment.label(coreC, danger) : status);
        v.put("danger", danger ? 1 : 0);
        v.put("synced", 1);
        putNullable(v, "skin_c", skinC);
        putNullable(v, "bpm", bpm);
        putNullable(v, "spo2", spo2);
        putNullable(v, "altitude_m", altitudeM);
        putNullable(v, "core_c", coreC);
        putNullable(v, "latitude", latitude);
        putNullable(v, "longitude", longitude);
        putNullable(v, "gps_accuracy_m", gpsAccuracyM);
        if (gpsAt == null) v.putNull("gps_at"); else v.put("gps_at", gpsAt);
        if (resolvedPhoto == null) v.putNull("photo_file"); else v.put("photo_file", resolvedPhoto);

        getWritableDatabase().insertWithOnConflict("records", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static void putNullable(ContentValues v, String key, Number value) {
        if (value == null) v.putNull(key);
        else if (value instanceof Float) v.put(key, (Float) value);
        else if (value instanceof Double) v.put(key, (Double) value);
        else v.put(key, value.intValue());
    }

    public List<Record> recent() { return query("SELECT * FROM records ORDER BY measured_at DESC LIMIT 200"); }
    public List<Record> pending() { return query("SELECT * FROM records WHERE synced=0 ORDER BY measured_at ASC LIMIT 50"); }

    private List<Record> query(String sql) {
        List<Record> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                Record r = new Record();
                r.id = c.getString(c.getColumnIndexOrThrow("id"));
                r.name = c.getString(c.getColumnIndexOrThrow("name"));
                r.status = c.getString(c.getColumnIndexOrThrow("status"));
                r.time = c.getLong(c.getColumnIndexOrThrow("measured_at"));
                r.skinC = nullableFloat(c, "skin_c");
                r.bpm = nullableInt(c, "bpm");
                r.spo2 = nullableInt(c, "spo2");
                r.altitudeM = nullableInt(c, "altitude_m");
                r.latitude = nullableDouble(c, "latitude");
                r.longitude = nullableDouble(c, "longitude");
                r.gpsAccuracyM = nullableFloat(c, "gps_accuracy_m");
                r.gpsAt = nullableLong(c, "gps_at");
                r.photoFile = c.getString(c.getColumnIndexOrThrow("photo_file"));
                r.coreC = nullableFloat(c, "core_c");
                r.danger = c.getInt(c.getColumnIndexOrThrow("danger")) != 0;
                r.synced = c.getInt(c.getColumnIndexOrThrow("synced")) != 0;
                rows.add(r);
            }
        }
        return rows;
    }

    private static Float nullableFloat(Cursor c, String key) {
        int i = c.getColumnIndexOrThrow(key);
        return c.isNull(i) ? null : c.getFloat(i);
    }

    private static Integer nullableInt(Cursor c, String key) {
        int i = c.getColumnIndexOrThrow(key);
        return c.isNull(i) ? null : c.getInt(i);
    }

    private static Double nullableDouble(Cursor c, String key) {
        int i = c.getColumnIndexOrThrow(key);
        return c.isNull(i) ? null : c.getDouble(i);
    }

    private static Long nullableLong(Cursor c, String key) {
        int i = c.getColumnIndexOrThrow(key);
        return c.isNull(i) ? null : c.getLong(i);
    }

    public int pendingCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM records WHERE synced=0", null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    public void markSynced(String id) {
        ContentValues v = new ContentValues();
        v.put("synced", 1);
        getWritableDatabase().update("records", v, "id=?", new String[]{id});
    }

    public void delete(String id) {
        getWritableDatabase().delete("records", "id=?", new String[]{id});
    }
}
