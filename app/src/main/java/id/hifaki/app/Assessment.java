package id.hifaki.app;

/** Clinical thresholds apply ONLY to verified core temperature, never to skin sensor data. */
public final class Assessment {
    private Assessment() { }

    public static String label(Float verifiedCoreC, boolean dangerSymptoms) {
        if (dangerSymptoms) return "DARURAT • gejala berbahaya";
        if (verifiedCoreC == null) return "Skrining • belum ada suhu inti";
        if (verifiedCoreC < 28f) return "DARURAT • hipotermia berat";
        if (verifiedCoreC < 32f) return "DARURAT • hipotermia sedang";
        if (verifiedCoreC < 35f) return "DARURAT • hipotermia ringan";
        return "Suhu inti ≥ 35°C • tetap nilai gejala";
    }

    public static boolean urgent(Float verifiedCoreC, boolean dangerSymptoms) {
        return dangerSymptoms || (verifiedCoreC != null && verifiedCoreC < 35f);
    }
}
