package dk.itu.datasys;

import java.util.Locale;

final class LogDuration {
    private LogDuration() {
    }

    static String formatMillisSince(long startNanos) {
        double elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000.0;
        return String.format(Locale.ROOT, "%.3f", elapsedMillis);
    }
}
