import java.util.Objects;

/** Single-threaded policy/lifecycle controller. Android I/O lives in its Port. */
final class WifiMonitor {
    static final long FALLBACK_MS = 7_000;
    static final long VERIFY_MS = 1_000;
    static final long EVENT_DEBOUNCE_MS = 250;
    enum State { PAUSED, NO_WIFI, IDLE, SUSPECT, VERIFY, COOLDOWN, CONFIG_ERROR, STOPPED }
    enum Attempt { ACCEPTED, REJECTED, STALE, ASLEEP }
    static final class Snapshot {
        final Object network;
        final String bssid;
        final int rssi;
        Snapshot(Object network, String bssid, int rssi) {
            this.network = network; this.bssid = bssid; this.rssi = rssi;
        }
        boolean valid() {
            return network != null && bssid != null && !"02:00:00:00:00:00".equals(bssid)
                && rssi >= -126 && rssi < 0;
        }
    }
    interface Port {
        long now();
        long epoch();
        boolean interactive();
        boolean singleWifi();
        Snapshot readWifi() throws Exception;
        Attempt disconnect(Snapshot expected, long epoch) throws Exception;
        void schedule(Runnable task, long delay);
        void cancel(Runnable task);
        void warn(Snapshot info, int threshold, long deadline);
        void cancelWarning();
        void clearFeedback();
        void result(String reason);
    }
    static final class Metrics {
        long rssiEvents, networkEvents, screenEvents, rssiReads, scheduledRuns, periodicRuns;
        long countdownStarts, countdownCancels, disconnectAttempts, disconnectAccepted;
        long staleSuppressed, lateTasksSuppressed, weakReconnects, readErrors;
        String lastCancel = "none", lastDisconnect = "none";
    }
    final Metrics metrics = new Metrics();
    private final Port port;
    private final WifiDisconnectPolicy policy = new WifiDisconnectPolicy();
    private final Runnable sampleTask = this::runSample;
    private final Runnable confirmationTask = this::confirmationTimeout;
    private State state = State.PAUSED;
    private boolean awake, stopped, scheduled, eventSample, observeConnection, forceRead, configured = true;
    private long dueAt, lastReadAt = Long.MIN_VALUE;
    private int threshold = -80;
    private Snapshot last, requested;
    private String requestedReason, lastDisconnectedBssid;

    WifiMonitor(Port port) { this.port = port; }
    State state() { return state; }
    int threshold() { return threshold; }
    Snapshot last() { return last; }
    long deadline() { return policy.deadline(); }
    long cooldownUntil() { return policy.cooldownUntil(); }
    boolean hasSampleTask() { return scheduled; }

    void screen(boolean on) {
        metrics.screenEvents++;
        if (stopped) return;
        // A screen-off event invalidates the complete session, including confirmation/UI.
        if (!on || !port.interactive()) { pause(); return; }
        if (awake) return;
        awake = true;
        forceRead = true;
        lastReadAt = Long.MIN_VALUE;
        cancelPending("screen-on restart");
        state = State.IDLE;
        if (!configured) { state = State.CONFIG_ERROR; return; }
        request(0, true);
    }

    void network(Object lostNetwork, boolean topologyChanged) {
        metrics.networkEvents++;
        if (stopped || !awake) return;
        if (!port.interactive()) { pause(); return; }
        if (requested != null && Objects.equals(requested.network, lostNetwork)) {
            requested = null;
            port.cancel(confirmationTask);
            port.result(requestedReason);
        }
        if (!configured) { state = State.CONFIG_ERROR; return; }
        if (topologyChanged) {
            cancelPending("network change");
            observeConnection = true;
            lastReadAt = Long.MIN_VALUE;
        }
        if (!port.singleWifi()) {
            unschedule(); cancelPending("no single Wi-Fi"); last = null;
            state = State.NO_WIFI;
            return;
        }
        event("network");
    }

    void rssiEvent() {
        metrics.rssiEvents++;
        event("rssi");
    }

    void refresh() {
        if (stopped || !awake || !configured) return;
        forceRead = true;
        request(0, true);
    }

    void threshold(int value) {
        if (value < -100 || value > -20) throw new IllegalArgumentException("Invalid threshold");
        boolean wasInvalid = !configured; configured = true;
        if (value == threshold) { if (wasInvalid && awake) request(0, true); return; }
        threshold = value;
        cancelPending("threshold change");
        if (awake && !stopped) request(0, true);
    }

    private void event(String kind) {
        if (stopped || !awake || !configured || !port.singleWifi()) return;
        if (policy.coolingDown(port.now()) && !observeConnection) return;
        if (!policy.pending()) state = State.SUSPECT;
        long earliest = lastReadAt == Long.MIN_VALUE ? 0 : Math.max(0, lastReadAt + VERIFY_MS - port.now());
        request(Math.max(EVENT_DEBOUNCE_MS, earliest), true);
    }

    private void request(long delay, boolean event) {
        if (!awake || stopped) return;
        long target = port.now() + Math.max(0, delay);
        if (scheduled && dueAt <= target) return;
        unschedule();
        scheduled = true; dueAt = target; eventSample = event;
        port.schedule(sampleTask, Math.max(0, target - port.now()));
    }

    private void unschedule() {
        port.cancel(sampleTask);
        scheduled = false;
    }

    private Snapshot read() throws Exception {
        metrics.rssiReads++;
        lastReadAt = port.now();
        Snapshot info = port.readWifi();
        return info != null && info.valid() ? info : null;
    }

    private void runSample() {
        boolean periodic = !eventSample;
        scheduled = false;
        if (stopped) return;
        if (!configured) { state = State.CONFIG_ERROR; return; }
        if (!awake || !port.interactive()) {
            metrics.lateTasksSuppressed++; pause(); return;
        }
        metrics.scheduledRuns++;
        if (periodic) metrics.periodicRuns++;
        if (!port.singleWifi()) {
            cancelPending("no single Wi-Fi"); state = State.NO_WIFI; last = null; return;
        }
        try {
            if (policy.coolingDown(port.now()) && !observeConnection && !forceRead) { next(); return; }
            long generation = port.epoch();
            Snapshot info = read();
            forceRead = false;
            if (!port.interactive()) { pause(); return; }
            if (generation != port.epoch()) {
                metrics.staleSuppressed++; request(VERIFY_MS, true); return;
            }
            if (info == null) { cancelPending("invalid Wi-Fi state"); last = null; next(); return; }
            if (policy.coolingDown(port.now())) {
                if (observeConnection && Objects.equals(lastDisconnectedBssid, info.bssid) && info.rssi <= threshold)
                    metrics.weakReconnects++;
                last = info; observeConnection = false; next(); return;
            }
            observeConnection = false;
            if (last != null && (!Objects.equals(last.network, info.network) || !Objects.equals(last.bssid, info.bssid)))
                cancelPending("AP identity change");
            last = info;
            WifiDisconnectPolicy.Decision d = policy.sample(info.bssid, info.rssi, threshold, port.now());
            if (d == WifiDisconnectPolicy.Decision.WARN) {
                metrics.countdownStarts++; port.warn(info, threshold, policy.deadline());
            } else if (d == WifiDisconnectPolicy.Decision.DISCONNECT) {
                attempt(false, generation);
            } else if (d == WifiDisconnectPolicy.Decision.CANCEL) {
                metrics.countdownCancels++; metrics.lastCancel = "signal recovered";
                port.cancelWarning();
            }
        } catch (Exception error) {
            metrics.readErrors++; cancelPending("read failure");
            if (!port.interactive()) { pause(); return; }
        }
        next();
    }

    boolean immediate() {
        if (stopped || !awake || !configured || !policy.pending()) return false;
        return attempt(true, port.epoch());
    }

    private boolean attempt(boolean manual, long generation) {
        if (!awake || !port.interactive()) { pause(); return false; }
        if (!port.singleWifi() || generation != port.epoch()) {
            metrics.staleSuppressed++; return false;
        }
        try {
            Snapshot fresh = read();  // Re-read; broadcast extras and old samples are never an authorization.
            if (!port.interactive()) { pause(); return false; }
            if (generation != port.epoch()) { metrics.staleSuppressed++; return false; }
            if (fresh == null || last == null || !Objects.equals(last.network, fresh.network)
                    || !policy.eligible(fresh.bssid, fresh.rssi, threshold, port.now(), manual)) {
                cancelPending("final state changed"); return false;
            }
            Attempt result = port.disconnect(fresh, generation);
            if (result == Attempt.ASLEEP) { pause(); return false; }
            if (result == Attempt.STALE) { metrics.staleSuppressed++; return false; }
            // Commit only after an actual Binder attempt. Failed preflight never consumes cooldown.
            policy.commit(port.now());
            unschedule();
            metrics.disconnectAttempts++;
            metrics.lastDisconnect = manual ? "manual" : "countdown";
            lastDisconnectedBssid = fresh.bssid;
            if (result == Attempt.ACCEPTED) {
                metrics.disconnectAccepted++;
                requested = fresh; requestedReason = manual ? "manual" : "auto";
                port.cancel(confirmationTask);
                port.schedule(confirmationTask, 4_000);
            } else {
                port.result("failed");
            }
            return result == Attempt.ACCEPTED;
        } catch (Exception e) {
            metrics.readErrors++; cancelPending("disconnect failure"); return false;
        } finally { next(); }
    }

    private void confirmationTimeout() {
        if (requested == null) return;
        requested = null;
        if (awake && port.interactive()) port.result("failed");
    }

    private void next() {
        if (!awake || stopped || !configured) return;
        if (!port.singleWifi()) { unschedule(); state = State.NO_WIFI; return; }
        if (policy.coolingDown(port.now())) {
            state = State.COOLDOWN;
            request(Math.max(1, policy.cooldownUntil() - port.now()), false);
        } else if (policy.pending()) {
            state = State.VERIFY;
            long remaining = policy.deadline() - port.now();
            request(remaining > 0 ? Math.min(VERIFY_MS, remaining) : VERIFY_MS, false);
        } else {
            state = State.IDLE;
            request(FALLBACK_MS, false);
        }
    }

    private void cancelPending(String reason) {
        if (policy.pending()) { metrics.countdownCancels++; metrics.lastCancel = reason; unschedule(); }
        policy.clear(); port.cancelWarning();
    }

    private void pause() {
        awake = false; unschedule(); cancelPending("screen off");
        requested = null; port.cancel(confirmationTask); port.clearFeedback();
        last = null; observeConnection = false; forceRead = false; state = State.PAUSED;
    }

    void stop() {
        pause(); stopped = true; state = State.STOPPED;
    }

    void invalidConfiguration() {
        configured = false; unschedule(); cancelPending("invalid threshold");
        if (awake) state = State.CONFIG_ERROR;
    }
}
