package id.hifaki.app;

public final class ProtocolTest {
    public static void main(String[] args) {
        byte[] p = new byte[12];
        p[0] = 1;
        put16(p, 1, 3363);
        put16(p, 3, 79);
        put16(p, 5, 42);
        p[7] = 15;
        p[8] = 80;
        put16(p, 9, 501);
        checksum(p);
        Reading r = Reading.parse(p, 1000);
        require(r != null && Math.abs(r.skinC - 33.63f) < 0.001f && r.bpm == 79 &&
                r.altitudeM == 42 && r.batteryPct == 80 && r.sequence == 501, "valid packet");
        p[11] ^= 1;
        require(Reading.parse(p, 1000) == null, "checksum rejection");
        put16(p, 1, -12700);
        put16(p, 3, 6000);
        p[7] = 7; // finger absent, even when pulse bytes contain a value
        p[8] = (byte) 255;
        checksum(p);
        r = Reading.parse(p, 2000);
        require(r != null && r.skinC == null && r.bpm == null && r.batteryPct == null,
                "invalid DS18B20 and implausible heart rate become missing");
        require(Assessment.label(null, false).startsWith("Skrining"), "skin cannot classify core hypothermia");
        require(Assessment.label(34.99f, false).contains("ringan"), "mild boundary");
        require(Assessment.label(31.99f, false).contains("sedang"), "moderate boundary");
        require(Assessment.label(27.99f, false).contains("berat"), "severe boundary");
        require(!Assessment.urgent(35f, false) && Assessment.urgent(36f, true), "symptoms override");
        System.out.println("Protocol and assessment tests passed");
    }

    private static void put16(byte[] p, int offset, int value) {
        p[offset] = (byte) value;
        p[offset + 1] = (byte) (value >> 8);
    }
    private static void checksum(byte[] p) {
        p[11] = 0;
        for (int i = 0; i < 11; i++) p[11] ^= p[i];
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
