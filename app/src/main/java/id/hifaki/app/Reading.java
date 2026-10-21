package id.hifaki.app;

/** Wire protocol v1: 12 bytes, little endian, XOR checksum over bytes 0..10. */
public final class Reading {
    public final Float skinC;
    public final Integer bpm;
    public final Integer altitudeM;
    public final Integer batteryPct;
    public final int sequence;
    public final long receivedAt;

    private Reading(Float skinC, Integer bpm, Integer altitudeM, Integer batteryPct,
                    int sequence, long receivedAt) {
        this.skinC = skinC;
        this.bpm = bpm;
        this.altitudeM = altitudeM;
        this.batteryPct = batteryPct;
        this.sequence = sequence;
        this.receivedAt = receivedAt;
    }

    public static Reading parse(byte[] p, long now) {
        if (p == null || p.length != 12 || (p[0] & 0xff) != 1) return null;
        int xor = 0;
        for (int i = 0; i < 11; i++) xor ^= p[i] & 0xff;
        if (xor != (p[11] & 0xff)) return null;

        int flags = p[7] & 0xff;
        int temperature = signed16(p, 1);
        int pulse = unsigned16(p, 3);
        int altitude = signed16(p, 5);
        int battery = p[8] & 0xff;
        Float skin = (flags & 1) != 0 && temperature >= -2000 && temperature <= 6000
                ? temperature / 100f : null;
        Integer heart = (flags & 2) != 0 && (flags & 8) != 0 && pulse >= 30 && pulse <= 220
                ? pulse : null;
        Integer height = (flags & 4) != 0 && altitude >= -500 && altitude <= 9000
                ? altitude : null;
        Integer charge = battery <= 100 ? battery : null;
        return new Reading(skin, heart, height, charge, unsigned16(p, 9), now);
    }

    private static int unsigned16(byte[] data, int at) {
        return (data[at] & 0xff) | ((data[at + 1] & 0xff) << 8);
    }

    private static int signed16(byte[] data, int at) {
        return (short) unsigned16(data, at);
    }

    public boolean hasData() { return skinC != null || bpm != null || altitudeM != null; }
}
