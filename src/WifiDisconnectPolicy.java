import java.util.Objects;

/** Timing and AP identity checks shared by sampling and the immediate-disconnect action. */
final class WifiDisconnectPolicy {
    static final long COUNTDOWN_MS = 7_000;
    static final long COOLDOWN_MS = 60_000;
    enum Decision { NONE, WARN, WAIT, CANCEL, DISCONNECT }

    private String bssid;
    private int threshold;
    private long deadline;
    private long nextDisconnectAt;
    private long countdownMs = COUNTDOWN_MS;

    void countdown(long millis) {
        if (millis < 1_000 || millis > 300_000) throw new IllegalArgumentException("Countdown must be 1..300 seconds");
        if (millis != countdownMs) { countdownMs = millis; clear(); }
    }
    long countdownMs() { return countdownMs; }

    Decision sample(String currentBssid, int rssi, int currentThreshold, long now) {
        boolean wasPending = pending();
        if (currentBssid == null || rssi > currentThreshold || now < nextDisconnectAt) {
            clear();
            return wasPending ? Decision.CANCEL : Decision.NONE;
        }
        if (!pending() || !Objects.equals(bssid, currentBssid) || threshold != currentThreshold) {
            bssid = currentBssid;
            threshold = currentThreshold;
            deadline = now + countdownMs;
            return Decision.WARN;
        }
        if (now >= deadline) {
            return Decision.DISCONNECT;
        }
        return Decision.WAIT;
    }

    boolean immediate(String currentBssid, int rssi, int currentThreshold, long now) {
        return eligible(currentBssid, rssi, currentThreshold, now, true);
    }

    boolean eligible(String currentBssid, int rssi, int currentThreshold, long now, boolean immediate) {
        return pending() && Objects.equals(bssid, currentBssid) && threshold == currentThreshold
            && rssi <= currentThreshold && now >= nextDisconnectAt && (immediate || now >= deadline);
    }

    void commit(long now) {
        nextDisconnectAt = now + COOLDOWN_MS;
        clear();
    }

    boolean pending() { return bssid != null; }
    long deadline() { return deadline; }
    long cooldownUntil() { return nextDisconnectAt; }
    boolean coolingDown(long now) { return now < nextDisconnectAt; }
    void clear() { bssid = null; deadline = 0; }
}
