package bucketbanish;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class Format {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private Format() {
    }

    static String bytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        String units = "KMGTPE";
        int exp = (int) (Math.log(n) / Math.log(1024));
        return String.format("%.1f %siB", n / Math.pow(1024, exp), units.charAt(exp - 1));
    }

    static String time(LocalDateTime t) {
        return t == null ? "(no date)              " : TIME.format(t);
    }

    static String seconds(java.time.Duration d) {
        return String.format("%.2fs", d.toMillis() / 1000.0);
    }
}
