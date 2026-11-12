import java.util.Objects;

/** Timing and AP identity checks shared by sampling and the immediate-disconnect action. */
final class WifiDisconnectPolicy {
    static final long COUNTDOWN_MS = 7_000;
    static final long COOLDOWN_MS = 60_000;
    static final int DEFAULT_RECOVERY_DB = 3;
    static final int MAX_RECOVERY_DB = 10;
    enum Decision { NONE, WARN, WAIT, CANCEL, DISCONNECT }

    private String bssid;
    private int threshold;
    private long deadline;
    private long nextDisconnectAt;
    private long countdownMs = COUNTDOWN_MS;
    private int recoveryDb = DEFAULT_RECOVERY_DB;

    void countdown(long millis) {
        if (millis < 1_000 || millis > 300_000) throw new IllegalArgumentException("Countdown must be 1..300 seconds");
        if (millis != countdownMs) { countdownMs = millis; clear(); }
    }
    long countdownMs() { return countdownMs; }

    void recoveryDb(int value) { recoveryDb = value; }
    int recoveryDb() { return recoveryDb; }
    boolean recovered(int rssi, int currentThreshold) { return rssi >= currentThreshold + recoveryDb; }

    Decision sample(String currentBssid, int rssi, int currentThreshold, long now) {
        boolean wasPending = pending();
        boolean sameSession = wasPending && Objects.equals(bssid, currentBssid) && threshold == currentThreshold;
        boolean strongEnough = sameSession ? recovered(rssi, currentThreshold) : rssi > currentThreshold;
        if (currentBssid == null || strongEnough || now < nextDisconnectAt) {
            clear();
            return wasPending ? Decision.CANCEL : Decision.NONE;
        }
        if (!sameSession) {
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
            && !recovered(rssi, currentThreshold) && now >= nextDisconnectAt && (immediate || now >= deadline);
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
